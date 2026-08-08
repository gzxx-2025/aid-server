// Package health 负责升级器健康文件（心跳）的维护。
package health

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// 健康状态常量，与后端 UpdaterClient 协议一致。
const (
	StatusRunning = "RUNNING"
	StatusStopped = "STOPPED"

	// ProtocolVersion 当前升级器协议版本
	ProtocolVersion = 3

	timeLayout = "2006-01-02 15:04:05"

	// 健康文件只保存轻量状态；限制旧文件读取大小，防止错误路径指向大文件。
	maxPreviousHealthBytes = 64 * 1024
)

// 任务状态常量。
const (
	TaskStateRunning = "RUNNING"
	TaskStateSuccess = "SUCCESS"
	TaskStateFailed  = "FAILED"
)

// LastTask 记录最近一次任务的执行结果，随健康文件透出给后端。
type LastTask struct {
	TaskID     string                 `json:"taskId"`
	Action     string                 `json:"action"`
	State      string                 `json:"state"`
	Message    string                 `json:"message"`
	Progress   int                    `json:"progress"`
	Phase      string                 `json:"phase,omitempty"`
	StartedAt  string                 `json:"startedAt,omitempty"`
	UpdatedAt  string                 `json:"updatedAt,omitempty"`
	FinishedAt string                 `json:"finishedAt,omitempty"`
	Checks     map[string]CheckResult `json:"checks,omitempty"`
}

// CheckResult 是部署配置分项诊断的脱敏结果。
type CheckResult struct {
	Status     string `json:"status"`
	Message    string `json:"message"`
	Suggestion string `json:"suggestion,omitempty"`
}

// DeploymentConfiguration 是允许后台展示的部署配置快照，不包含任何密码或密钥原文。
type DeploymentConfiguration struct {
	Mode              string            `json:"mode"`
	ConfigPath        string            `json:"configPath"`
	DefaultConfigPath string            `json:"defaultConfigPath"`
	AllowedConfigRoot string            `json:"allowedConfigRoot"`
	Values            map[string]string `json:"values"`
	ConfiguredSecrets []string          `json:"configuredSecrets"`
}

type payload struct {
	Status           string                   `json:"status"`
	Version          string                   `json:"version"`
	ProtocolVersion  int                      `json:"protocolVersion"`
	ServiceManager   string                   `json:"serviceManager,omitempty"`
	UpdatedAt        string                   `json:"updatedAt"`
	UpdatedAtEpochMs int64                    `json:"updatedAtEpochMs"`
	LastTask         *LastTask                `json:"lastTask,omitempty"`
	Configuration    *DeploymentConfiguration `json:"configuration,omitempty"`
}

// Reporter 周期性写健康文件，并承载最近任务状态。
type Reporter struct {
	filePath       string
	version        string
	serviceManager string

	mu            sync.Mutex
	lastTask      *LastTask
	configuration *DeploymentConfiguration
}

// SetConfiguration 更新脱敏后的部署配置快照并立即刷新健康文件。
func (r *Reporter) SetConfiguration(configuration *DeploymentConfiguration) {
	r.mu.Lock()
	r.configuration = cloneConfiguration(configuration)
	r.mu.Unlock()
	r.write(StatusRunning)
}

// NewReporter 创建健康报告器；serviceManager 为部署方式标识（systemd/docker），随心跳透出。
func NewReporter(filePath string, version string, serviceManager string) *Reporter {
	return &Reporter{
		filePath: filePath, version: version, serviceManager: serviceManager,
		lastTask: loadPreviousTask(filePath),
	}
}

// loadPreviousTask 在升级器重启后保留最终任务结果。自升级会主动退出并由
// systemd/Docker 拉起新进程，若直接清空 lastTask，页面会把刚完成的任务误判为未知。
func loadPreviousTask(filePath string) *LastTask {
	info, err := os.Stat(filePath)
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > maxPreviousHealthBytes {
		return nil
	}
	raw, err := os.ReadFile(filePath)
	if err != nil {
		return nil
	}
	var previous payload
	if err := json.Unmarshal(raw, &previous); err != nil || previous.LastTask == nil {
		return nil
	}
	task := *previous.LastTask
	return &task
}

// Start 启动心跳协程，ctx 结束时写入 STOPPED 状态。
func (r *Reporter) Start(ctx context.Context, interval time.Duration) {
	r.write(StatusRunning)
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				r.write(StatusStopped)
				return
			case <-ticker.C:
				r.write(StatusRunning)
			}
		}
	}()
}

// SetTask 更新最近任务状态并立即刷新健康文件。
func (r *Reporter) SetTask(taskID, action, state, message string) {
	now := time.Now().Format(timeLayout)
	r.mu.Lock()
	startedAt := now
	progress := 0
	phase := "准备执行"
	if r.lastTask != nil && r.lastTask.TaskID == taskID {
		startedAt = r.lastTask.StartedAt
		progress = r.lastTask.Progress
		phase = r.lastTask.Phase
	}
	var checks map[string]CheckResult
	if r.lastTask != nil && r.lastTask.TaskID == taskID {
		checks = cloneChecks(r.lastTask.Checks)
	}
	if state == TaskStateSuccess {
		progress = 100
		phase = "执行完成"
	}
	if state == TaskStateFailed && phase == "" {
		phase = "执行失败"
	}
	task := &LastTask{
		TaskID: taskID, Action: action, State: state, Message: message,
		Progress: progress, Phase: phase, StartedAt: startedAt, UpdatedAt: now, Checks: checks,
	}
	if state != TaskStateRunning {
		task.FinishedAt = now
	}
	r.lastTask = task
	r.mu.Unlock()
	r.write(StatusRunning)
}

// SetTaskChecks 保存分项诊断结果，结果只包含状态、摘要和操作建议。
func (r *Reporter) SetTaskChecks(taskID, action string, checks map[string]CheckResult) {
	r.mu.Lock()
	if r.lastTask != nil && r.lastTask.TaskID == taskID && r.lastTask.Action == action {
		r.lastTask.Checks = cloneChecks(checks)
	}
	r.mu.Unlock()
	r.write(StatusRunning)
}

// SetTaskProgress 更新运行任务的阶段与百分比。百分比只允许单调前进，避免页面倒退。
func (r *Reporter) SetTaskProgress(taskID, action string, progress int, phase, message string) {
	if progress < 0 {
		progress = 0
	}
	if progress > 99 {
		progress = 99
	}
	now := time.Now().Format(timeLayout)
	r.mu.Lock()
	startedAt := now
	if r.lastTask != nil && r.lastTask.TaskID == taskID {
		startedAt = r.lastTask.StartedAt
		if progress < r.lastTask.Progress {
			progress = r.lastTask.Progress
		}
	}
	r.lastTask = &LastTask{
		TaskID: taskID, Action: action, State: TaskStateRunning, Message: message,
		Progress: progress, Phase: phase, StartedAt: startedAt, UpdatedAt: now,
	}
	r.mu.Unlock()
	r.write(StatusRunning)
}

// Flush 以指定状态立即写一次健康文件（退出前使用）。
func (r *Reporter) Flush(status string) {
	r.write(status)
}

func (r *Reporter) write(status string) {
	now := time.Now()
	r.mu.Lock()
	body := payload{
		Status:           status,
		Version:          r.version,
		ProtocolVersion:  ProtocolVersion,
		ServiceManager:   r.serviceManager,
		UpdatedAt:        now.Format(timeLayout),
		UpdatedAtEpochMs: now.UnixMilli(),
		LastTask:         r.lastTask,
		Configuration:    cloneConfiguration(r.configuration),
	}
	r.mu.Unlock()

	raw, err := json.MarshalIndent(body, "", "  ")
	if err != nil {
		log.Printf("序列化健康文件失败: %v", err)
		return
	}
	if err := atomicWrite(r.filePath, raw); err != nil {
		log.Printf("写入健康文件失败: %v", err)
	}
}

func cloneConfiguration(source *DeploymentConfiguration) *DeploymentConfiguration {
	if source == nil {
		return nil
	}
	values := make(map[string]string, len(source.Values))
	for key, value := range source.Values {
		values[key] = value
	}
	return &DeploymentConfiguration{
		Mode:              source.Mode,
		ConfigPath:        source.ConfigPath,
		DefaultConfigPath: source.DefaultConfigPath,
		AllowedConfigRoot: source.AllowedConfigRoot,
		Values:            values,
		ConfiguredSecrets: append([]string(nil), source.ConfiguredSecrets...),
	}
}

func cloneChecks(source map[string]CheckResult) map[string]CheckResult {
	if source == nil {
		return nil
	}
	result := make(map[string]CheckResult, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

// atomicWrite 通过临时文件+改名保证读方不会看到半截内容。
func atomicWrite(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("创建目录失败: %w", err)
	}
	tmp, err := os.CreateTemp(dir, ".health-*.tmp")
	if err != nil {
		return fmt.Errorf("创建临时文件失败: %w", err)
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("写临时文件失败: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭临时文件失败: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("替换健康文件失败: %w", err)
	}
	return nil
}
