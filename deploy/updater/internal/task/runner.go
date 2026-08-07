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

// reportProgress 同步任务阶段到健康文件并写入统一日志，供后台与 aid.sh 实时展示。
func (r *Runner) reportProgress(t *Task, progress int, phase, message string) {
	log.Printf("[进度 %d%%] %s: %s", progress, phase, message)
	r.reporter.SetTaskProgress(t.TaskID, t.Action, progress, phase, message)
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
	if err != nil {
		log.Printf("扫描中断任务文件失败: %v", err)
		matches = nil
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
	// 任务进程异常退出时可能遗留提交锁。恢复记录与已认领任务处理完后再清理，
	// 让后端可以重新投递；若 inbox 中仍有原任务，下一轮会重新认领。
	if err := os.Remove(r.taskRunningMarker()); err != nil && !os.IsNotExist(err) {
		log.Printf("清理中断任务提交锁失败: %v", err)
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
		if err := os.Remove(r.taskRunningMarker()); err != nil && !os.IsNotExist(err) {
			log.Printf("清理任务提交锁失败: %v", err)
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
	r.reportProgress(t, 1, "任务已受理", "升级器已认领任务，正在准备执行")

	var runErr error
	switch t.Action {
	case ActionUpgrade:
		runErr = r.runApply(t, false)
	case ActionRollback:
		runErr = r.runApply(t, true)
	case ActionUpdaterUpgrade:
		runErr = r.runSelfUpgrade(t)
	case ActionConfigValidate:
		runErr = r.runConfigValidate(t)
	case ActionConfigApply:
		runErr = r.runConfigApply(t)
	case ActionConfigRollback:
		runErr = r.runConfigRollback()
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
	message := fmt.Sprintf("已完成 %s -> %s", t.SourceVersion, t.TargetVersion)
	switch t.Action {
	case ActionConfigValidate:
		message = "配置校验通过"
	case ActionConfigApply:
		message = "配置已应用并完成重启"
	case ActionConfigRollback:
		message = "已恢复上一份配置并完成重启"
	}
	r.reporter.SetTask(t.TaskID, t.Action, health.TaskStateSuccess, message)
	return true
}

// claim 先创建后端可见的运行锁，再把任务原子移入工作目录，堵住“已认领但
// 健康文件尚未来得及写 RUNNING”的极短窗口，避免第二个任务覆盖升级过程。
func (r *Runner) claim() (string, error) {
	if err := os.MkdirAll(r.cfg.WorkDir, 0o755); err != nil {
		return "", fmt.Errorf("创建工作目录失败: %w", err)
	}
	marker := r.taskRunningMarker()
	markerFile, err := os.OpenFile(marker, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return "", fmt.Errorf("创建任务提交锁失败: %w", err)
	}
	if closeErr := markerFile.Close(); closeErr != nil {
		_ = os.Remove(marker)
		return "", fmt.Errorf("写入任务提交锁失败: %w", closeErr)
	}
	claimed := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("claimed-%d.json", time.Now().UnixNano()))
	if err := os.Rename(r.cfg.TaskFile, claimed); err != nil {
		_ = os.Remove(marker)
		return "", fmt.Errorf("移动任务文件失败: %w", err)
	}
	return claimed, nil
}

func (r *Runner) taskRunningMarker() string {
	return r.cfg.TaskFile + ".running"
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
func (r *Runner) restoreAndReport(t *Task, s *backup.Snapshot, cause error, recoveryPath string, databaseDirty bool) error {
	log.Printf("开始回滚: 原因=%v", cause)
	r.reportProgress(t, 95, "自动恢复", "升级失败，正在恢复升级前版本")
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
	// 前端静态产物已还原为旧版本，Docker 静态 Web 容器 / nginx 同样需要重启生效
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
