package task

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"aid-updater/internal/backup"
)

type recoveryRecord struct {
	Task          Task             `json:"task"`
	Snapshot      *backup.Snapshot `json:"snapshot"`
	DatabaseDirty bool             `json:"databaseDirty"`
	Completed     bool             `json:"completed"`
}

func (r *Runner) createRecovery(t *Task, snapshot *backup.Snapshot) (string, error) {
	path := filepath.Join(r.cfg.WorkDir, "recovery-"+t.TaskID+".json")
	record := &recoveryRecord{Task: *t, Snapshot: snapshot}
	if err := writeJSONAtomic(path, record); err != nil {
		return "", fmt.Errorf("写入恢复记录失败: %w", err)
	}
	return path, nil
}

func markDatabaseDirty(path string) error {
	return updateRecovery(path, func(record *recoveryRecord) {
		record.DatabaseDirty = true
	})
}

func markRecoveryCompleted(path string) error {
	return updateRecovery(path, func(record *recoveryRecord) {
		record.Completed = true
	})
}

func updateRecovery(path string, update func(*recoveryRecord)) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	record := &recoveryRecord{}
	if err := json.Unmarshal(raw, record); err != nil {
		return err
	}
	update(record)
	return writeJSONAtomic(path, record)
}

func writeJSONAtomic(path string, value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".recovery-*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if _, err := tmp.Write(raw); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

func (r *Runner) loadRecovery(path string) (*recoveryRecord, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	record := &recoveryRecord{}
	if err := json.Unmarshal(raw, record); err != nil {
		return nil, err
	}
	if record.Snapshot == nil || strings.TrimSpace(record.Snapshot.Dir) == "" {
		return nil, fmt.Errorf("恢复记录缺少快照")
	}
	backupRoot, err := filepath.Abs(r.cfg.BackupDir)
	if err != nil {
		return nil, err
	}
	snapshotDir, err := filepath.Abs(record.Snapshot.Dir)
	if err != nil {
		return nil, err
	}
	rel, err := filepath.Rel(backupRoot, snapshotDir)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return nil, fmt.Errorf("恢复快照不在备份目录内")
	}
	return record, nil
}
