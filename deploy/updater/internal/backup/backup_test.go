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

func TestManagedBackupNameMatchesCreatedTags(t *testing.T) {
	for _, tag := range []string{"upgrade-v1.0.0-beta.5", "rollback-v1.0.0-beta.4"} {
		name := "20260809010101.123456789-" + safeTag(tag)
		if !managedBackupNamePattern.MatchString(name) {
			t.Fatalf("managed backup name was not recognized: %s", name)
		}
	}
	if managedBackupNamePattern.MatchString("20260809010101.123456789-manual-backup") {
		t.Fatal("unknown timestamp directory must not be treated as managed")
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

func TestPruneOldBackupsKeepsUnknownDirectoriesAndSymlinks(t *testing.T) {
	root := t.TempDir()
	unknown := filepath.Join(root, "manual-backup")
	if err := os.Mkdir(unknown, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{
		"20260809010101.000000001-upgrade-a",
		"20260809010102.000000002-upgrade-b",
		"20260809010103.000000003-rollback-c",
	} {
		if err := os.Mkdir(filepath.Join(root, name), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	external := t.TempDir()
	link := filepath.Join(root, "20260809010100.000000000-upgrade-link")
	if err := os.Symlink(external, link); err != nil {
		t.Logf("symlink unavailable: %v", err)
		link = ""
	}

	pruneOldBackups(root, 2)
	if _, err := os.Stat(unknown); err != nil {
		t.Fatalf("unknown backup directory must be preserved: %v", err)
	}
	if link != "" {
		if _, err := os.Lstat(link); err != nil {
			t.Fatalf("backup symlink must be preserved: %v", err)
		}
		if _, err := os.Stat(external); err != nil {
			t.Fatalf("symlink target must remain untouched: %v", err)
		}
	}
	if _, err := os.Stat(filepath.Join(root, "20260809010101.000000001-upgrade-a")); !os.IsNotExist(err) {
		t.Fatalf("oldest managed backup should be pruned, stat error: %v", err)
	}
}

func TestCreateRejectsSymlinkBackupRoot(t *testing.T) {
	realRoot := t.TempDir()
	linkRoot := filepath.Join(t.TempDir(), "backups")
	if err := os.Symlink(realRoot, linkRoot); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	cfg := &config.Config{BackupDir: linkRoot, KeepBackups: 3}
	if _, err := Create(cfg, "upgrade"); err == nil {
		t.Fatal("symlink backup root must be rejected")
	}
}

func TestPrepareBackupRootRejectsFilesystemRoot(t *testing.T) {
	root := filepath.Clean(filepath.VolumeName(t.TempDir()) + string(os.PathSeparator))
	if _, err := prepareBackupRoot(root); err == nil {
		t.Fatalf("filesystem root must be rejected: %s", root)
	}
}
