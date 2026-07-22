package task

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"aid-updater/internal/backup"
	"aid-updater/internal/config"
	"aid-updater/internal/health"
	"aid-updater/internal/sysctl"
)

// Runner 消费任务文件并执行升级动作。
type Runner struct {
	cfg      *config.Config
	reporter *health.Reporter
	version  string
	// exitRequested 自升级成功后置位，主循环据此退出进程交由 systemd 拉起新版本
	exitRequested bool
}

// NewRunner 创建任务执行器。
func NewRunner(cfg *config.Config, reporter *health.Reporter, version string) *Runner {
	return &Runner{cfg: cfg, reporter: reporter, version: version}
}

// ExitRequested 返回是否需要退出进程（自升级完成后）。
func (r *Runner) ExitRequested() bool {
	return r.exitRequested
}

// RecoverInterrupted 启动时优先恢复中断任务的文件与数据库，再清理认领文件。
func (r *Runner) RecoverInterrupted() {
	recoveryPattern := filepath.Join(r.cfg.WorkDir, "recovery-*.json")
	recoveryFiles, _ := filepath.Glob(recoveryPattern)
	for _, path := range recoveryFiles {
		record, loadErr := r.loadRecovery(path)
		if loadErr != nil {
			log.Printf("读取中断恢复记录失败 %s: %v", path, loadErr)
			continue
		}
		if record.Completed {
			if err := os.Remove(path); err != nil {
				log.Printf("清理已完成任务的恢复记录失败: %v", err)
			}
			continue
		}
		log.Printf("恢复中断任务 %s(%s)", record.Task.TaskID, record.Task.Action)
		var restoreErr error
		if record.DatabaseDirty {
			if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
				log.Printf("恢复中断任务前停止服务失败，继续尝试恢复: %v", stopErr)
			}
			restoreErr = backup.RestoreDatabase(r.cfg, record.Snapshot)
		}
		if restoreErr == nil {
			restoreErr = backup.Restore(r.cfg, record.Snapshot)
		}
		if restoreErr == nil {
			restoreErr = startBackend(r.cfg)
		}
		if restoreErr != nil {
			log.Printf("中断任务恢复失败，保留恢复记录等待人工处理: %v", restoreErr)
			r.reporter.SetTask(record.Task.TaskID, record.Task.Action, health.TaskStateFailed,
				trimMessage("升级中断且自动恢复失败: "+restoreErr.Error()))
			continue
		}
		if err := restartAuxServices(r.cfg); err != nil {
			log.Printf("中断恢复后重启附属服务失败: %v", err)
		}
		r.reporter.SetTask(record.Task.TaskID, record.Task.Action, health.TaskStateFailed,
			"升级器中断，已自动恢复原版本")
		if err := os.Remove(path); err != nil {
			log.Printf("清理恢复记录失败: %v", err)
		}
	}

	pattern := filepath.Join(r.cfg.WorkDir, "claimed-*.json")
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) == 0 {
		return
	}
	for _, path := range matches {
		if t, parseErr := Parse(path); parseErr == nil {
			log.Printf("发现中断任务 %s(%s)，标记为失败", t.TaskID, t.Action)
			r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateFailed,
				"任务因升级器中断未完成，请确认系统状态后重新发起")
		}
		if err := os.Remove(path); err != nil {
			log.Printf("清理中断任务文件失败: %v", err)
		}
	}
}

// PollOnce 检查任务文件，存在则认领并执行；返回是否执行了任务。
func (r *Runner) PollOnce() bool {
	if _, err := os.Stat(r.cfg.TaskFile); err != nil {
		return false
	}
	claimed, err := r.claim()
	if err != nil {
		log.Printf("认领任务失败: %v", err)
		return false
	}
	defer func() {
		if err := os.Remove(claimed); err != nil && !os.IsNotExist(err) {
			log.Printf("清理任务文件失败: %v", err)
		}
	}()

	t, err := Parse(claimed)
	if err != nil {
		log.Printf("任务解析失败: %v", err)
		r.reporter.SetTask("unknown", "UNKNOWN", health.TaskStateFailed, fmt.Sprintf("任务解析失败: %v", err))
		return true
	}

	log.Printf("开始执行任务 %s: %s %s -> %s", t.TaskID, t.Action, t.SourceVersion, t.TargetVersion)
	r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateRunning, "任务执行中")

	var runErr error
	switch t.Action {
	case ActionUpgrade:
		runErr = r.runApply(t, false)
	case ActionRollback:
		runErr = r.runApply(t, true)
	case ActionUpdaterUpgrade:
		runErr = r.runSelfUpgrade(t)
	}

	if runErr != nil {
		log.Printf("任务 %s 执行失败: %v", t.TaskID, runErr)
		r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateFailed, trimMessage(runErr.Error()))
		return true
	}
	if t.Action == ActionUpdaterUpgrade {
		// 自升级成功：先落成功状态再退出进程，交由 systemd 拉起新版本
		r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateSuccess,
			fmt.Sprintf("升级器已更新 %s -> %s，正在重启", t.SourceVersion, t.TargetVersion))
		r.exitRequested = true
		return true
	}
	log.Printf("任务 %s 执行成功", t.TaskID)
	r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateSuccess,
		fmt.Sprintf("已完成 %s -> %s", t.SourceVersion, t.TargetVersion))
	return true
}

// claim 将任务文件原子移入工作目录，避免执行中被后端视为可再次投递。
func (r *Runner) claim() (string, error) {
	if err := os.MkdirAll(r.cfg.WorkDir, 0o755); err != nil {
		return "", fmt.Errorf("创建工作目录失败: %w", err)
	}
	claimed := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("claimed-%d.json", time.Now().UnixNano()))
	if err := os.Rename(r.cfg.TaskFile, claimed); err != nil {
		return "", fmt.Errorf("移动任务文件失败: %w", err)
	}
	return claimed, nil
}

// cleanupWork 清理本次任务的下载与解压产物。
func (r *Runner) cleanupWork(paths ...string) {
	for _, p := range paths {
		if p == "" {
			continue
		}
		if err := os.RemoveAll(p); err != nil {
			log.Printf("清理工作产物失败 %s: %v", p, err)
		}
	}
}

// restoreAndReport 升级失败后还原备份并重启服务，返回给用户的失败说明。
func (r *Runner) restoreAndReport(s *backup.Snapshot, cause error, recoveryPath string, databaseDirty bool) error {
	log.Printf("开始回滚: 原因=%v", cause)
	if databaseDirty {
		if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
			return fmt.Errorf("升级失败(%v)，恢复数据库前停止服务失败(%v)，请人工介入，备份目录: %s", cause, stopErr, s.Dir)
		}
		if dbRestoreErr := backup.RestoreDatabase(r.cfg, s); dbRestoreErr != nil {
			return fmt.Errorf("升级失败(%v)，且数据库还原失败(%v)，请人工介入，备份目录: %s", cause, dbRestoreErr, s.Dir)
		}
	}
	if restoreErr := backup.Restore(r.cfg, s); restoreErr != nil {
		return fmt.Errorf("升级失败(%v)，且备份还原失败(%v)，请人工介入，备份目录: %s", cause, restoreErr, s.Dir)
	}
	if startErr := startBackend(r.cfg); startErr != nil {
		return fmt.Errorf("升级失败(%v)，已还原备份但服务启动失败(%v)，请人工检查", cause, startErr)
	}
	// 前端产物已还原为旧版本，附属服务（用户端 SSR / nginx）同样需要重启生效
	if err := restartAuxServices(r.cfg); err != nil {
		log.Printf("自动回滚后重启附属服务失败: %v", err)
	}
	if recoveryPath != "" {
		if err := os.Remove(recoveryPath); err != nil && !os.IsNotExist(err) {
			log.Printf("清理恢复记录失败: %v", err)
		}
	}
	return fmt.Errorf("升级失败已自动回滚到原版本(%v)", cause)
}

func trimMessage(message string) string {
	const maxLen = 500
	message = strings.TrimSpace(message)
	// 按字符截断，避免切坏多字节 UTF-8
	if runes := []rune(message); len(runes) > maxLen {
		return string(runes[:maxLen])
	}
	return message
}
