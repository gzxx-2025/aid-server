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
	"aid-updater/internal/manifest"
)

// runSelfUpgrade 升级器自升级：拉清单选平台制品→下载校验→解压→原子自替换。
// 成功后由主循环退出进程，systemd(Restart=always) 拉起新版本。
func (r *Runner) runSelfUpgrade(t *Task) error {
	if strings.TrimSpace(t.ManifestURL) == "" {
		return fmt.Errorf("任务缺少更新清单地址")
	}
	m, err := manifest.Fetch(t.ManifestURL, 30*time.Second)
	if err != nil {
		return err
	}
	pkg, err := m.SelectUpdaterPackage()
	if err != nil {
		return err
	}

	archivePath := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("updater-%s.tar.gz", t.TaskID))
	extractDir := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("updater-extract-%s", t.TaskID))
	defer r.cleanupWork(archivePath, extractDir)

	log.Printf("下载升级器新版本: %s", pkg.URL)
	if _, err := artifact.DownloadFile(pkg.URL, archivePath,
		time.Duration(r.cfg.DownloadTimeoutSeconds)*time.Second); err != nil {
		return fmt.Errorf("下载升级器失败: %w", err)
	}
	if err := artifact.VerifySHA256(archivePath, pkg.SHA256); err != nil {
		return fmt.Errorf("升级器校验失败: %w", err)
	}
	if err := artifact.ExtractTarGz(archivePath, extractDir); err != nil {
		return fmt.Errorf("解压升级器失败: %w", err)
	}
	newBinary, err := locateUpdaterBinary(extractDir)
	if err != nil {
		return err
	}

	if err := replaceSelf(newBinary); err != nil {
		return err
	}
	log.Printf("升级器已替换为 %s，进程即将退出交由 systemd 重启", m.Updater.Version)
	return nil
}

// locateUpdaterBinary 在解压目录内定位升级器可执行文件。
func locateUpdaterBinary(dir string) (string, error) {
	var found string
	err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		name := strings.ToLower(d.Name())
		if name == "aid-updater" || name == "aid-updater.exe" {
			found = path
			return filepath.SkipAll
		}
		return nil
	})
	if err != nil {
		return "", fmt.Errorf("查找升级器可执行文件失败: %w", err)
	}
	if found == "" {
		return "", fmt.Errorf("升级器包内未找到 aid-updater 可执行文件")
	}
	return found, nil
}

// replaceSelf 原子替换当前可执行文件：同目录写入新文件后两次改名，避免跨分区问题。
func replaceSelf(newBinary string) error {
	selfPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("获取自身路径失败: %w", err)
	}
	selfPath, err = filepath.EvalSymlinks(selfPath)
	if err != nil {
		return fmt.Errorf("解析自身路径失败: %w", err)
	}

	stagedPath := selfPath + ".new"
	oldPath := selfPath + ".old"
	if err := backup.CopyFile(newBinary, stagedPath); err != nil {
		return fmt.Errorf("暂存新版本失败: %w", err)
	}
	if err := os.Chmod(stagedPath, 0o755); err != nil {
		return fmt.Errorf("设置可执行权限失败: %w", err)
	}
	// Linux 允许替换运行中的可执行文件：先移走旧文件再落位新文件
	if err := os.Rename(selfPath, oldPath); err != nil {
		os.Remove(stagedPath)
		return fmt.Errorf("移走旧版本失败: %w", err)
	}
	if err := os.Rename(stagedPath, selfPath); err != nil {
		// 尽力恢复旧版本，保证升级器本身仍可用
		if restoreErr := os.Rename(oldPath, selfPath); restoreErr != nil {
			return fmt.Errorf("落位新版本失败(%v)且恢复旧版本失败(%v)，请人工修复: %s", err, restoreErr, selfPath)
		}
		return fmt.Errorf("落位新版本失败，已恢复旧版本: %w", err)
	}
	return nil
}

// CleanupSelfUpgradeLeftover 清理上次自升级遗留的旧版本文件（新进程启动时调用）。
func CleanupSelfUpgradeLeftover() {
	selfPath, err := os.Executable()
	if err != nil {
		return
	}
	if resolved, err := filepath.EvalSymlinks(selfPath); err == nil {
		selfPath = resolved
	}
	oldPath := selfPath + ".old"
	if _, err := os.Stat(oldPath); err == nil {
		if err := os.Remove(oldPath); err != nil {
			log.Printf("清理旧版本升级器失败: %v", err)
		}
	}
}
