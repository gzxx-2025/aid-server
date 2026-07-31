// Package task 定义升级任务模型与执行调度。
package task

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"strings"
)

var taskIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)

// 任务动作常量，与后端 SystemUpgradeServiceImpl 协议一致。
const (
	ActionUpgrade        = "UPGRADE"
	ActionUpdaterUpgrade = "UPDATER_UPGRADE"
	ActionRollback       = "ROLLBACK"

	// SupportedSchemaVersion 当前支持的任务结构版本
	SupportedSchemaVersion = 1
)

// Task 为后端投递的升级任务。
type Task struct {
	SchemaVersion int    `json:"schemaVersion"`
	TaskID        string `json:"taskId"`
	Action        string `json:"action"`
	SourceVersion string `json:"sourceVersion"`
	TargetVersion string `json:"targetVersion"`
	RequestedAt   string `json:"requestedAt"`

	// ManifestURL 更新清单地址（UPGRADE/UPDATER_UPGRADE 附带）
	ManifestURL string `json:"manifestUrl,omitempty"`
	// DownloadURL 升级器发布页（UPDATER_UPGRADE 附带，仅用于提示）
	DownloadURL string `json:"downloadUrl,omitempty"`
	// PackageURL 制品直链（UPGRADE/ROLLBACK 附带）
	PackageURL string `json:"packageUrl,omitempty"`
	// SHA256 制品校验值（UPGRADE/ROLLBACK 附带）
	SHA256 string `json:"sha256,omitempty"`

	// DatabaseCompatible 回退目标版本数据库是否兼容（ROLLBACK 附带）
	DatabaseCompatible *bool `json:"databaseCompatible,omitempty"`
	// DatabaseRollback 数据库回退脚本名（ROLLBACK 附带，位于包内 sql/ 下）
	DatabaseRollback string `json:"databaseRollback,omitempty"`
	// BackupRequired 是否强制备份（ROLLBACK 附带）
	BackupRequired bool `json:"backupRequired,omitempty"`
	// KeepBackups 备份保留份数（后台「升级源配置」下发，0 表示沿用升级器本地配置）
	KeepBackups int `json:"keepBackups,omitempty"`
}

// Parse 从文件解析任务并做基础校验。
func Parse(path string) (*Task, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取任务文件失败: %w", err)
	}
	t := &Task{}
	if err := json.Unmarshal(raw, t); err != nil {
		return nil, fmt.Errorf("解析任务JSON失败: %w", err)
	}
	if t.SchemaVersion != SupportedSchemaVersion {
		return nil, fmt.Errorf("任务结构版本不支持: %d", t.SchemaVersion)
	}
	if !taskIDPattern.MatchString(strings.TrimSpace(t.TaskID)) {
		return nil, fmt.Errorf("任务缺少 taskId")
	}
	switch t.Action {
	case ActionUpgrade, ActionUpdaterUpgrade, ActionRollback:
	default:
		return nil, fmt.Errorf("未知任务动作: %s", t.Action)
	}
	return t, nil
}
