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
	ProtocolVersion = 1

	timeLayout = "2006-01-02 15:04:05"
)

// 任务状态常量。
const (
	TaskStateRunning = "RUNNING"
	TaskStateSuccess = "SUCCESS"
	TaskStateFailed  = "FAILED"
)

// LastTask 记录最近一次任务的执行结果，随健康文件透出给后端。
type LastTask struct {
	TaskID     string `json:"taskId"`
	Action     string `json:"action"`
	State      string `json:"state"`
	Message    string `json:"message"`
	FinishedAt string `json:"finishedAt,omitempty"`
}

type payload struct {
	Status          string    `json:"status"`
	Version         string    `json:"version"`
	ProtocolVersion int       `json:"protocolVersion"`
	ServiceManager  string    `json:"serviceManager,omitempty"`
	UpdatedAt       string    `json:"updatedAt"`
	LastTask        *LastTask `json:"lastTask,omitempty"`
}

// Reporter 周期性写健康文件，并承载最近任务状态。
type Reporter struct {
	filePath       string
	version        string
	serviceManager string

	mu       sync.Mutex
	lastTask *LastTask
}

// NewReporter 创建健康报告器；serviceManager 为部署方式标识（systemd/docker），随心跳透出。
func NewReporter(filePath string, version string, serviceManager string) *Reporter {
	return &Reporter{filePath: filePath, version: version, serviceManager: serviceManager}
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
	r.mu.Lock()
	task := &LastTask{TaskID: taskID, Action: action, State: state, Message: message}
	if state != TaskStateRunning {
		task.FinishedAt = time.Now().Format(timeLayout)
	}
	r.lastTask = task
	r.mu.Unlock()
	r.write(StatusRunning)
}

// Flush 以指定状态立即写一次健康文件（退出前使用）。
func (r *Reporter) Flush(status string) {
	r.write(status)
}

func (r *Reporter) write(status string) {
	r.mu.Lock()
	body := payload{
		Status:          status,
		Version:         r.version,
		ProtocolVersion: ProtocolVersion,
		ServiceManager:  r.serviceManager,
		UpdatedAt:       time.Now().Format(timeLayout),
		LastTask:        r.lastTask,
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
