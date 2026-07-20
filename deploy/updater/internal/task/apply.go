package task

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"aid-updater/internal/artifact"
	"aid-updater/internal/backup"
	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
	"aid-updater/internal/sysctl"
)

// 升级包内的固定布局。
const (
	pkgBackendDir = "backend"
	pkgAdminDir   = "admin-dist"
	pkgWebDir     = "web-dist"
	pkgSQLDir     = "sql"
)

// runApply 执行系统升级或版本回退：下载→校验→解压→备份→停服→替换→SQL→启动→健康检查，失败自动回滚。
func (r *Runner) runApply(t *Task, isRollback bool) error {
	if strings.TrimSpace(t.PackageURL) == "" || strings.TrimSpace(t.SHA256) == "" {
		return fmt.Errorf("任务缺少制品直链或校验值")
	}

	// 1. 下载并校验
	archivePath := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("pkg-%s.tar.gz", t.TaskID))
	extractDir := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("extract-%s", t.TaskID))
	defer r.cleanupWork(archivePath, extractDir)

	log.Printf("下载升级包: %s", t.PackageURL)
	if _, err := artifact.DownloadFile(t.PackageURL, archivePath,
		time.Duration(r.cfg.DownloadTimeoutSeconds)*time.Second); err != nil {
		return fmt.Errorf("下载升级包失败: %w", err)
	}
	if err := artifact.VerifySHA256(archivePath, t.SHA256); err != nil {
		return fmt.Errorf("升级包校验失败: %w", err)
	}

	// 2. 解压并校验包布局
	if err := artifact.ExtractTarGz(archivePath, extractDir); err != nil {
		return fmt.Errorf("解压升级包失败: %w", err)
	}
	packageRoot, err := locatePackageRoot(extractDir)
	if err != nil {
		return err
	}
	newJar, err := locateBackendJar(packageRoot)
	if err != nil {
		return err
	}

	// 3. 数据库前置校验：需要执行 SQL 但未启用数据库配置时，提前失败（此时尚未停服，无损）
	sqlDir := filepath.Join(packageRoot, pkgSQLDir)
	rollbackScript := ""
	if isRollback {
		if t.DatabaseCompatible == nil || !*t.DatabaseCompatible {
			rollbackScript = strings.TrimSpace(t.DatabaseRollback)
			if rollbackScript == "" {
				return fmt.Errorf("目标版本数据库不兼容且未提供回退脚本")
			}
			if !r.cfg.Database.Enabled {
				return fmt.Errorf("回退需执行数据库脚本，请先在升级器配置中启用 database")
			}
			if _, statErr := os.Stat(filepath.Join(sqlDir, rollbackScript)); statErr != nil {
				return fmt.Errorf("回退包内缺少数据库回退脚本: %s", rollbackScript)
			}
		}
	} else if hasSQLScripts(sqlDir) && !r.cfg.Database.Enabled {
		return fmt.Errorf("升级包含数据库变更，请先在升级器配置中启用 database")
	}

	// 4. 备份（含可选数据库备份）
	tag := "upgrade"
	if isRollback {
		tag = "rollback"
	}
	snapshot, err := backup.Create(r.cfg, fmt.Sprintf("%s-%s", tag, t.TargetVersion))
	if err != nil {
		return fmt.Errorf("备份失败，已中止: %w", err)
	}
	log.Printf("备份完成: %s", snapshot.Dir)

	// 5. 停服并替换产物；此后任何失败都走自动回滚
	if err := sysctl.StopService(r.cfg.Install.BackendService); err != nil {
		return fmt.Errorf("停止服务失败: %w", err)
	}
	if err := r.replaceArtifacts(packageRoot, newJar); err != nil {
		return r.restoreAndReport(snapshot, err)
	}

	// 6. 数据库变更
	if isRollback {
		if rollbackScript != "" {
			if err := dbexec.ExecuteScript(r.cfg.Database, filepath.Join(sqlDir, rollbackScript)); err != nil {
				return r.restoreAndReport(snapshot, fmt.Errorf("执行数据库回退脚本失败: %w", err))
			}
		}
	} else if r.cfg.Database.Enabled {
		count, err := dbexec.ExecuteDir(r.cfg.Database, sqlDir)
		if err != nil {
			return r.restoreAndReport(snapshot, fmt.Errorf("执行增量SQL失败: %w", err))
		}
		if count > 0 {
			log.Printf("已执行 %d 个增量SQL脚本", count)
		}
	}

	// 7. 启动并健康检查
	if err := startBackend(r.cfg); err != nil {
		return r.restoreAndReport(snapshot, fmt.Errorf("启动服务失败: %w", err))
	}
	if err := sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second); err != nil {
		if stopErr := sysctl.StopService(r.cfg.Install.BackendService); stopErr != nil {
			log.Printf("健康检查失败后停止服务失败: %v", stopErr)
		}
		return r.restoreAndReport(snapshot, fmt.Errorf("新版本健康检查失败: %w", err))
	}
	return nil
}

// replaceArtifacts 用包内产物替换部署位置的三端产物。
func (r *Runner) replaceArtifacts(packageRoot, newJar string) error {
	if err := backup.CopyFile(newJar, r.cfg.Install.BackendJar); err != nil {
		return fmt.Errorf("替换服务端jar失败: %w", err)
	}
	adminSrc := filepath.Join(packageRoot, pkgAdminDir)
	if r.cfg.Install.AdminDist != "" && dirExists(adminSrc) {
		if err := backup.ReplaceDir(adminSrc, r.cfg.Install.AdminDist); err != nil {
			return fmt.Errorf("替换管理端失败: %w", err)
		}
	}
	webSrc := filepath.Join(packageRoot, pkgWebDir)
	if r.cfg.Install.WebDist != "" && dirExists(webSrc) {
		if err := backup.ReplaceDir(webSrc, r.cfg.Install.WebDist); err != nil {
			return fmt.Errorf("替换用户端失败: %w", err)
		}
	}
	return nil
}

// locatePackageRoot 兼容"产物在压缩包根目录"与"产物在单层子目录"两种打包方式。
func locatePackageRoot(extractDir string) (string, error) {
	if dirExists(filepath.Join(extractDir, pkgBackendDir)) {
		return extractDir, nil
	}
	entries, err := os.ReadDir(extractDir)
	if err != nil {
		return "", fmt.Errorf("读取解压目录失败: %w", err)
	}
	var dirs []string
	for _, entry := range entries {
		if entry.IsDir() {
			dirs = append(dirs, entry.Name())
		}
	}
	if len(dirs) == 1 && dirExists(filepath.Join(extractDir, dirs[0], pkgBackendDir)) {
		return filepath.Join(extractDir, dirs[0]), nil
	}
	return "", fmt.Errorf("升级包布局非法: 缺少 %s/ 目录", pkgBackendDir)
}

// locateBackendJar 在包内 backend/ 下定位唯一的 jar。
func locateBackendJar(packageRoot string) (string, error) {
	backendDir := filepath.Join(packageRoot, pkgBackendDir)
	entries, err := os.ReadDir(backendDir)
	if err != nil {
		return "", fmt.Errorf("读取包内 backend 目录失败: %w", err)
	}
	var jars []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(strings.ToLower(entry.Name()), ".jar") {
			jars = append(jars, entry.Name())
		}
	}
	if len(jars) != 1 {
		return "", fmt.Errorf("包内 backend 目录应有且仅有一个jar, 实际 %d 个", len(jars))
	}
	return filepath.Join(backendDir, jars[0]), nil
}

func hasSQLScripts(dir string) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(strings.ToLower(entry.Name()), ".sql") {
			return true
		}
	}
	return false
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func startBackend(cfg *config.Config) error {
	return sysctl.StartService(cfg.Install.BackendService)
}
