// Package backup 负责升级前备份与失败还原。
package backup

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
)

// Snapshot 描述一次备份的内容与位置。
type Snapshot struct {
	// Dir 本次备份目录
	Dir string
	// HasJar 是否备份了服务端 jar
	HasJar bool
	// HasAdminDist / HasWebDist 是否备份了前端目录
	HasAdminDist bool
	HasWebDist   bool
	// DBDumpFile 数据库备份文件（未启用数据库配置时为空）
	DBDumpFile string
}

// Create 备份当前部署的三端产物与（可选）数据库。
func Create(cfg *config.Config, tag string) (*Snapshot, error) {
	dir := filepath.Join(cfg.BackupDir, fmt.Sprintf("%s-%s", time.Now().Format("20060102150405"), tag))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建备份目录失败: %w", err)
	}
	snapshot := &Snapshot{Dir: dir}

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
	return snapshot, nil
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

func replaceDir(src, dst string) error {
	if err := os.RemoveAll(dst); err != nil {
		return fmt.Errorf("清理旧目录失败: %w", err)
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

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}
