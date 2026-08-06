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
