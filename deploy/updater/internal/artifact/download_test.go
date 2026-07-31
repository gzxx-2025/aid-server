package artifact

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestDownloadAndVerifyFallsBackAfterChecksumFailure(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "artifact.tar.gz")
	expected := fmt.Sprintf("%x", sha256.Sum256([]byte("valid-package")))
	var attempts []string

	download := func(source, target string, _ time.Duration) (int64, error) {
		attempts = append(attempts, source)
		body := []byte("invalid-package")
		if source == "https://mirror.example/package" {
			body = []byte("valid-package")
		}
		if err := os.WriteFile(target, body, 0o600); err != nil {
			return 0, err
		}
		return int64(len(body)), nil
	}

	selected, _, err := downloadAndVerify([]string{
		"https://primary.example/package",
		"https://mirror.example/package",
	}, dst, expected, time.Second, download)
	if err != nil {
		t.Fatalf("expected mirror fallback to succeed: %v", err)
	}
	if selected != "https://mirror.example/package" {
		t.Fatalf("unexpected selected source: %s", selected)
	}
	if len(attempts) != 2 {
		t.Fatalf("expected two attempts, got %d", len(attempts))
	}
}

func TestUniqueSourcesKeepsOrderAndRemovesDuplicates(t *testing.T) {
	actual := uniqueSources([]string{" https://primary.example/a ", "", "https://primary.example/a", "https://mirror.example/a"})
	if len(actual) != 2 || actual[0] != "https://primary.example/a" || actual[1] != "https://mirror.example/a" {
		t.Fatalf("unexpected sources: %#v", actual)
	}
}
