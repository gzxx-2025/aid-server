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
	"aid-updater/internal/manifest"
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
	if err := verifyApplyTask(t, isRollback); err != nil {
		return err
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
			if filepath.Base(rollbackScript) != rollbackScript ||
				!strings.HasSuffix(strings.ToLower(rollbackScript), ".sql") {
				return fmt.Errorf("数据库回退脚本名称非法")
			}
			if _, statErr := os.Stat(filepath.Join(sqlDir, rollbackScript)); statErr != nil {
				return fmt.Errorf("回退包内缺少数据库回退脚本: %s", rollbackScript)
			}
		}
	} else if hasSQLScripts(sqlDir) && !r.cfg.Database.Enabled {
		return fmt.Errorf("升级包含数据库变更，请先在升级器配置中启用 database")
	}

	// 4. 备份（含可选数据库备份；数据库备份必须先于任何 SQL 变更）；
	//    保留份数以后台「升级源配置」随任务下发的值优先，未下发时用本地配置
	if t.KeepBackups > 0 {
		r.cfg.KeepBackups = t.KeepBackups
	}
	tag := "upgrade"
	if isRollback {
		tag = "rollback"
	}
	snapshot, err := backup.Create(r.cfg, fmt.Sprintf("%s-%s", tag, t.TargetVersion))
	if err != nil {
		return fmt.Errorf("备份失败，已中止: %w", err)
	}
	log.Printf("备份完成: %s", snapshot.Dir)
	recoveryPath, err := r.createRecovery(t, snapshot)
	if err != nil {
		return err
	}
	databaseDirty := false

	// 5. 升级的增量 SQL 在停服前执行（发布规范要求增量只做加法、与旧版本代码兼容），
	//    把停机窗口压缩到「替换文件 + 启动」；此时失败服务仍在运行，直接中止零影响。
	//    执行记录表（aid_schema_history）保证重试与跨版本包携带旧脚本时不会重复执行。
	if !isRollback && r.cfg.Database.Enabled && hasSQLScripts(sqlDir) {
		if err := markDatabaseDirty(recoveryPath); err != nil {
			return fmt.Errorf("更新恢复记录失败: %w", err)
		}
		databaseDirty = true
		count, err := dbexec.ExecuteDir(r.cfg.Database, sqlDir)
		if err != nil {
			if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
				log.Printf("恢复数据库前停止服务失败: %v", stopErr)
			}
			restoreErr := backup.RestoreDatabase(r.cfg, snapshot)
			startErr := startBackend(r.cfg)
			if restoreErr != nil {
				return fmt.Errorf("执行增量SQL失败(%v)，数据库恢复失败(%v)，备份目录: %s", err, restoreErr, snapshot.Dir)
			}
			if startErr != nil {
				return fmt.Errorf("执行增量SQL失败，数据库已恢复但服务启动失败: %v", startErr)
			}
			_ = os.Remove(recoveryPath)
			return fmt.Errorf("执行增量SQL失败，已恢复数据库: %w", err)
		}
		if count > 0 {
			log.Printf("已执行 %d 个增量SQL脚本", count)
		}
	}

	// 6. 停服并替换产物；此后任何失败都走自动回滚
	if err := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); err != nil {
		return r.restoreAndReport(snapshot, fmt.Errorf("停止服务失败: %w", err), recoveryPath, databaseDirty)
	}
	if err := r.replaceArtifacts(packageRoot, newJar); err != nil {
		return r.restoreAndReport(snapshot, err, recoveryPath, databaseDirty)
	}

	// 版本回退的数据库回退脚本可能收缩结构，必须在停服后执行
	if isRollback && rollbackScript != "" {
		if err := markDatabaseDirty(recoveryPath); err != nil {
			return r.restoreAndReport(snapshot, fmt.Errorf("更新恢复记录失败: %w", err), recoveryPath, false)
		}
		databaseDirty = true
		if err := dbexec.ExecuteScript(r.cfg.Database, filepath.Join(sqlDir, rollbackScript)); err != nil {
			return r.restoreAndReport(snapshot, fmt.Errorf("执行数据库回退脚本失败: %w", err), recoveryPath, databaseDirty)
		}
	}

	// 7. 启动并健康检查
	if err := startBackend(r.cfg); err != nil {
		return r.restoreAndReport(snapshot, fmt.Errorf("启动服务失败: %w", err), recoveryPath, databaseDirty)
	}
	if err := sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second); err != nil {
		if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
			log.Printf("健康检查失败后停止服务失败: %v", stopErr)
		}
		return r.restoreAndReport(snapshot, fmt.Errorf("新版本健康检查失败: %w", err), recoveryPath, databaseDirty)
	}

	// 8. 重启附属服务使新产物生效：用户端 SSR、docker 部署的 nginx 等
	//    核心服务已健康后先提交恢复记录，避免清理失败导致下次启动误回滚。
	if err := markRecoveryCompleted(recoveryPath); err != nil {
		if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
			log.Printf("提交恢复记录失败后停止服务失败: %v", stopErr)
		}
		return r.restoreAndReport(snapshot, fmt.Errorf("提交升级完成状态失败: %w", err), recoveryPath, databaseDirty)
	}
	auxErr := restartAuxServices(r.cfg)
	if err := os.Remove(recoveryPath); err != nil && !os.IsNotExist(err) {
		log.Printf("清理已完成任务的恢复记录失败: %v", err)
	}
	if auxErr != nil {
		return auxErr
	}
	return nil
}

func verifyApplyTask(t *Task, isRollback bool) error {
	if strings.TrimSpace(t.ManifestURL) == "" {
		return fmt.Errorf("任务缺少签名清单地址")
	}
	m, err := manifest.Fetch(t.ManifestURL, 30*time.Second)
	if err != nil {
		return fmt.Errorf("验证升级清单失败: %w", err)
	}
	if !isRollback {
		if m.ProductVersion != t.TargetVersion || m.PackageURL != t.PackageURL ||
			!strings.EqualFold(m.PackageSHA256, t.SHA256) {
			return fmt.Errorf("升级任务与签名清单不一致")
		}
		return nil
	}
	for _, release := range m.RollbackReleases {
		if release.Version == t.TargetVersion && release.PackageURL == t.PackageURL &&
			strings.EqualFold(release.SHA256, t.SHA256) {
			return nil
		}
	}
	return fmt.Errorf("回退任务不在签名清单中")
}

// restartAuxServices 依次重启配置的附属服务；未配置时跳过。
func restartAuxServices(cfg *config.Config) error {
	var failures []string
	for _, raw := range cfg.Install.RestartServices {
		service := strings.TrimSpace(raw)
		if service == "" {
			continue
		}
		if err := sysctl.StopService(cfg.Install.ServiceManager, service); err != nil {
			log.Printf("停止附属服务失败（请人工重启 %s）: %v", service, err)
			failures = append(failures, service+"停止失败")
		}
		if err := sysctl.StartService(cfg.Install.ServiceManager, service); err != nil {
			log.Printf("启动附属服务失败（请人工重启 %s）: %v", service, err)
			failures = append(failures, service+"启动失败")
			continue
		}
		log.Printf("附属服务已重启: %s", service)
	}
	if len(failures) > 0 {
		return fmt.Errorf("核心升级完成，但附属服务异常: %s", strings.Join(failures, ", "))
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
	return sysctl.StartService(cfg.Install.ServiceManager, cfg.Install.BackendService)
}
