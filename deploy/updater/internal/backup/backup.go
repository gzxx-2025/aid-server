// Package backup 负责升级前备份与失败还原。
package backup

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"

	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
)

// Snapshot 描述一次备份的内容与位置。
type Snapshot struct {
	// Dir 本次备份目录
	Dir string `json:"dir"`
	// HasJar 是否备份了服务端 jar
	HasJar bool `json:"hasJar"`
	// HasAdminDist / HasWebDist 是否备份了前端目录
	HasAdminDist bool `json:"hasAdminDist"`
	HasWebDist   bool `json:"hasWebDist"`
	// DBDumpFile 数据库备份文件（未启用数据库配置时为空）
	DBDumpFile string `json:"dbDumpFile,omitempty"`
}

// RestoreDatabase 将快照中的数据库备份恢复到当前数据库。
func RestoreDatabase(cfg *config.Config, s *Snapshot) error {
	if s == nil || s.DBDumpFile == "" {
		return nil
	}
	if !cfg.Database.Enabled {
		return fmt.Errorf("数据库恢复配置未启用")
	}
	if err := dbexec.Restore(cfg.Database, s.DBDumpFile); err != nil {
		return err
	}
	return nil
}

// Create 备份当前部署的三端产物与（可选）数据库，并按保留份数清理过期备份。
func Create(cfg *config.Config, tag string) (snapshot *Snapshot, err error) {
	dir := filepath.Join(cfg.BackupDir, fmt.Sprintf("%s-%s", time.Now().Format("20060102150405.000000000"), safeTag(tag)))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建备份目录失败: %w", err)
	}
	// 备份中途失败时删除半成品目录：残留会被当作有效备份挤占保留份数
	defer func() {
		if err != nil {
			if removeErr := os.RemoveAll(dir); removeErr != nil {
				log.Printf("清理半成品备份失败 %s: %v", dir, removeErr)
			}
		}
	}()
	snapshot = &Snapshot{Dir: dir}

	if fileExists(cfg.Install.BackendJar) {
		if err := copyFile(cfg.Install.BackendJar, filepath.Join(dir, "aid-admin.jar")); err != nil {
			return nil, fmt.Errorf("备份服务端jar失败: %w", err)
		}
		snapshot.HasJar = true
	}
	if cfg.Install.AdminDist != "" && dirExists(cfg.Install.AdminDist) {
		if err := copyDir(cfg.Install.AdminDist, filepath.Join(dir, "admin-dist")); err != nil {
			return nil, fmt.Errorf("备份管理端失败: %w", err)
		}
		snapshot.HasAdminDist = true
	}
	if cfg.Install.WebDist != "" && dirExists(cfg.Install.WebDist) {
		if err := copyDir(cfg.Install.WebDist, filepath.Join(dir, "web-dist")); err != nil {
			return nil, fmt.Errorf("备份用户端失败: %w", err)
		}
		snapshot.HasWebDist = true
	}
	if cfg.Database.Enabled {
		dumpFile := filepath.Join(dir, "database.sql")
		if err := dbexec.Dump(cfg.Database, dumpFile); err != nil {
			return nil, fmt.Errorf("备份数据库失败: %w", err)
		}
		snapshot.DBDumpFile = dumpFile
	}
	// 本次备份完整落盘后才清理过期备份：备份中途失败不能折损既有备份存量
	pruneOldBackups(cfg.BackupDir, cfg.KeepBackups)
	return snapshot, nil
}

func safeTag(tag string) string {
	tag = strings.TrimSpace(tag)
	if tag == "" {
		return "snapshot"
	}
	cleaned := strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || r == '.' || r == '-' || r == '_' {
			return r
		}
		return '_'
	}, tag)
	for strings.Contains(cleaned, "..") {
		cleaned = strings.ReplaceAll(cleaned, "..", "_")
	}
	return cleaned
}

// Restore 将备份内容还原到部署位置（数据库不自动还原，仅提示人工处理）。
func Restore(cfg *config.Config, s *Snapshot) error {
	if s == nil {
		return fmt.Errorf("无可用备份")
	}
	if s.HasJar {
		if err := copyFile(filepath.Join(s.Dir, "aid-admin.jar"), cfg.Install.BackendJar); err != nil {
			return fmt.Errorf("还原服务端jar失败: %w", err)
		}
	}
	if s.HasAdminDist {
		if err := replaceDir(filepath.Join(s.Dir, "admin-dist"), cfg.Install.AdminDist); err != nil {
			return fmt.Errorf("还原管理端失败: %w", err)
		}
	}
	if s.HasWebDist {
		if err := replaceDir(filepath.Join(s.Dir, "web-dist"), cfg.Install.WebDist); err != nil {
			return fmt.Errorf("还原用户端失败: %w", err)
		}
	}
	return nil
}

// ReplaceDir 用 src 目录整体替换 dst 目录（对外提供给任务执行器复用）。
func ReplaceDir(src, dst string) error {
	return replaceDir(src, dst)
}

// CopyFile 覆盖复制单个文件（对外提供给任务执行器复用）。
func CopyFile(src, dst string) error {
	return copyFile(src, dst)
}

// replaceDir 用 src 内容替换 dst 目录：保留 dst 目录本身只清空内容——
// dst 可能是容器 bind mount 的挂载源，删除目录本身会让容器内挂载点失效。
func replaceDir(src, dst string) error {
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return fmt.Errorf("创建目标目录失败: %w", err)
	}
	entries, err := os.ReadDir(dst)
	if err != nil {
		return fmt.Errorf("读取目标目录失败: %w", err)
	}
	for _, entry := range entries {
		if err := os.RemoveAll(filepath.Join(dst, entry.Name())); err != nil {
			return fmt.Errorf("清理旧目录内容失败: %w", err)
		}
	}
	// 跨分区 rename 会失败，直接递归复制最稳妥
	return copyDir(src, dst)
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Sync()
}

func copyDir(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		if !d.Type().IsRegular() {
			// 跳过符号链接等特殊文件
			return nil
		}
		return copyFile(path, target)
	})
}

// pruneOldBackups 备份目录只保留最近 keep 份（目录名以时间戳开头，字典序即时间序），
// 防止多次升级后备份无限增长写满磁盘；清理失败仅告警不阻断升级。
func pruneOldBackups(backupRoot string, keep int) {
	if keep <= 0 {
		return
	}
	entries, err := os.ReadDir(backupRoot)
	if err != nil {
		return
	}
	var dirs []string
	for _, entry := range entries {
		if entry.IsDir() {
			dirs = append(dirs, entry.Name())
		}
	}
	// 含本次新建的目录在内保留 keep 份
	if len(dirs) <= keep {
		return
	}
	sort.Strings(dirs)
	for _, name := range dirs[:len(dirs)-keep] {
		target := filepath.Join(backupRoot, name)
		if err := os.RemoveAll(target); err != nil {
			log.Printf("清理过期备份失败 %s: %v", target, err)
			continue
		}
		log.Printf("已清理过期备份: %s", target)
	}
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}
