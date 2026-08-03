package backup

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"aid-updater/internal/config"
)

func TestSafeTagRemovesPathSeparators(t *testing.T) {
	got := safeTag("rollback-../../target")
	if strings.ContainsAny(got, `/\\`) || strings.Contains(got, "..") {
		t.Fatalf("unsafe backup tag: %s", got)
	}
}

func TestCreateAndRestoreBuildInfo(t *testing.T) {
	root := t.TempDir()
	appDir := filepath.Join(root, "app")
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		t.Fatal(err)
	}
	jarPath := filepath.Join(appDir, "aid-admin.jar")
	buildInfoPath := filepath.Join(appDir, "build-info.json")
	if err := os.WriteFile(jarPath, []byte("jar-v1"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(buildInfoPath, []byte(`{"version":"1.0.0"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{
		BackupDir:   filepath.Join(root, "backups"),
		KeepBackups: 3,
		Install:     config.Install{BackendJar: jarPath},
	}

	snapshot, err := Create(cfg, "upgrade-1.1.0")
	if err != nil {
		t.Fatal(err)
	}
	if !snapshot.HasBuildInfo {
		t.Fatal("build-info.json should be included in the snapshot")
	}
	if err := os.WriteFile(buildInfoPath, []byte(`{"version":"1.1.0"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := Restore(cfg, snapshot); err != nil {
		t.Fatal(err)
	}
	restored, err := os.ReadFile(buildInfoPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(restored) != `{"version":"1.0.0"}` {
		t.Fatalf("unexpected restored build info: %s", restored)
	}
}

func TestRestoreLegacySnapshotRemovesNewBuildInfo(t *testing.T) {
	root := t.TempDir()
	appDir := filepath.Join(root, "app")
	legacyDir := filepath.Join(root, "legacy-backup")
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(legacyDir, 0o755); err != nil {
		t.Fatal(err)
	}
	jarPath := filepath.Join(appDir, "aid-admin.jar")
	buildInfoPath := filepath.Join(appDir, "build-info.json")
	if err := os.WriteFile(filepath.Join(legacyDir, "aid-admin.jar"), []byte("legacy-jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(jarPath, []byte("new-jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(buildInfoPath, []byte(`{"version":"new"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{Install: config.Install{BackendJar: jarPath}}
	legacy := &Snapshot{Dir: legacyDir, HasJar: true}
	if err := Restore(cfg, legacy); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(buildInfoPath); !os.IsNotExist(err) {
		t.Fatalf("legacy rollback should remove stale build info, stat error: %v", err)
	}
}
