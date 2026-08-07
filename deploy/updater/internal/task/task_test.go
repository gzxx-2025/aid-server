package task

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"aid-updater/internal/config"
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

func TestValidateFrontendArtifactsRequiresStaticIndexes(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{Install: config.Install{
		AdminDist: "/data/aid/app/admin-dist",
		WebDist:   "/data/aid/app/web-dist",
	}}
	if err := os.MkdirAll(filepath.Join(root, pkgAdminDir), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, pkgWebDir), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, pkgAdminDir, "index.html"), []byte("admin"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := validateFrontendArtifacts(root, cfg); err == nil {
		t.Fatal("expected missing Web static index to be rejected")
	}
	if err := os.WriteFile(filepath.Join(root, pkgWebDir, "index.html"), []byte("web"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := validateFrontendArtifacts(root, cfg); err == nil {
		t.Fatal("expected missing Web SPA entry to be rejected")
	}
	if err := os.WriteFile(filepath.Join(root, pkgWebDir, "200.html"), []byte("web-spa"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := validateFrontendArtifacts(root, cfg); err != nil {
		t.Fatalf("expected complete static artifacts, got %v", err)
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

func TestClaimCreatesRunningMarkerBeforeRemovingInboxTask(t *testing.T) {
	root := t.TempDir()
	taskFile := filepath.Join(root, "inbox", "task.json")
	workDir := filepath.Join(root, "work")
	if err := os.MkdirAll(filepath.Dir(taskFile), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(taskFile, []byte(`{"schemaVersion":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	runner := &Runner{cfg: &config.Config{TaskFile: taskFile, WorkDir: workDir}}

	claimed, err := runner.claim()
	if err != nil {
		t.Fatalf("claim task: %v", err)
	}
	if _, err := os.Stat(runner.taskRunningMarker()); err != nil {
		t.Fatalf("running marker must exist after claim: %v", err)
	}
	if _, err := os.Stat(taskFile); !os.IsNotExist(err) {
		t.Fatalf("inbox task must be moved, stat err=%v", err)
	}
	if _, err := os.Stat(claimed); err != nil {
		t.Fatalf("claimed task must exist: %v", err)
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
