package task

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestParseRejectsUnsafeTaskID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "task.json")
	raw := `{"schemaVersion":1,"taskId":"../../outside","action":"UPGRADE"}`
	if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(path); err == nil {
		t.Fatal("expected unsafe task id to be rejected")
	}
}

func TestParseSourceBuildTask(t *testing.T) {
	path := filepath.Join(t.TempDir(), "task.json")
	raw := `{"schemaVersion":1,"taskId":"source-1","action":"UPGRADE","targetVersion":"1.0.0-beta.3","buildFromSource":true}`
	if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	parsed, err := Parse(path)
	if err != nil {
		t.Fatal(err)
	}
	if !parsed.BuildFromSource || parsed.TargetVersion != "1.0.0-beta.3" {
		t.Fatalf("unexpected source task: %#v", parsed)
	}
}

func TestRecoveryCompletedStatePersists(t *testing.T) {
	path := filepath.Join(t.TempDir(), "recovery.json")
	record := &recoveryRecord{Task: Task{TaskID: "task-1"}}
	if err := writeJSONAtomic(path, record); err != nil {
		t.Fatal(err)
	}
	if err := markRecoveryCompleted(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	stored := &recoveryRecord{}
	if err := json.Unmarshal(raw, stored); err != nil {
		t.Fatal(err)
	}
	if !stored.Completed {
		t.Fatal("expected recovery record to be completed")
	}
}
