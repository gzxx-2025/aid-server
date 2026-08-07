package health

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReporterWritesTimezoneIndependentHeartbeat(t *testing.T) {
	path := filepath.Join(t.TempDir(), "health.json")
	reporter := NewReporter(path, "1.0.0", "docker")
	before := time.Now().UnixMilli()
	reporter.Flush(StatusRunning)
	after := time.Now().UnixMilli()

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read health file: %v", err)
	}
	var health payload
	if err := json.Unmarshal(raw, &health); err != nil {
		t.Fatalf("parse health file: %v", err)
	}
	if health.UpdatedAtEpochMs < before || health.UpdatedAtEpochMs > after {
		t.Fatalf("unexpected epoch heartbeat: %d, range=[%d,%d]", health.UpdatedAtEpochMs, before, after)
	}
	if health.UpdatedAt == "" {
		t.Fatal("human-readable heartbeat must remain available")
	}
}

func TestReporterKeepsTaskProgressMonotonicAndCompletes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "health.json")
	reporter := NewReporter(path, "1.0.0", "docker")
	reporter.SetTask("task-1", "UPGRADE", TaskStateRunning, "任务执行中")
	reporter.SetTaskProgress("task-1", "UPGRADE", 60, "创建备份", "正在创建备份")
	reporter.SetTaskProgress("task-1", "UPGRADE", 50, "校验制品", "不应倒退")
	reporter.SetTask("task-1", "UPGRADE", TaskStateSuccess, "升级完成")

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read health file: %v", err)
	}
	var health payload
	if err := json.Unmarshal(raw, &health); err != nil {
		t.Fatalf("parse health file: %v", err)
	}
	if health.LastTask == nil || health.LastTask.Progress != 100 {
		t.Fatalf("completed task progress must be 100: %#v", health.LastTask)
	}
	if health.LastTask.StartedAt == "" || health.LastTask.UpdatedAt == "" || health.LastTask.FinishedAt == "" {
		t.Fatalf("task timestamps must be present: %#v", health.LastTask)
	}
}

func TestReporterPreservesCompletedTaskAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "health.json")
	reporter := NewReporter(path, "1.0.0", "docker")
	reporter.SetTask("self-upgrade-1", "UPDATER_UPGRADE", TaskStateRunning, "正在升级")
	reporter.SetTaskProgress("self-upgrade-1", "UPDATER_UPGRADE", 98, "重启升级器", "新版本已就位")
	reporter.SetTask("self-upgrade-1", "UPDATER_UPGRADE", TaskStateSuccess, "升级完成")

	restarted := NewReporter(path, "1.0.1", "docker")
	restarted.Flush(StatusRunning)

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read restarted health file: %v", err)
	}
	var health payload
	if err := json.Unmarshal(raw, &health); err != nil {
		t.Fatalf("parse restarted health file: %v", err)
	}
	if health.LastTask == nil || health.LastTask.TaskID != "self-upgrade-1" {
		t.Fatalf("completed task must survive updater restart: %#v", health.LastTask)
	}
	if health.LastTask.State != TaskStateSuccess || health.LastTask.Progress != 100 {
		t.Fatalf("preserved task must keep final result: %#v", health.LastTask)
	}
}
