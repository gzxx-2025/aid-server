package artifact

import (
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
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

func TestDownloadFileWithLimitRejectsInvalidLimitBeforeNetwork(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "artifact")
	if _, err := DownloadFileWithLimit("https://example.com/artifact", dst, time.Second, 0); err == nil {
		t.Fatal("zero byte limit unexpectedly accepted")
	}
	if _, err := DownloadFileWithLimit("https://example.com/artifact", dst, time.Second, maxDownloadBytes+1); err == nil {
		t.Fatal("oversized caller limit unexpectedly accepted")
	}
}

func TestDownloadFileWithLimitRejectsChunkedOversizeBody(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.(http.Flusher).Flush()
		_, _ = w.Write([]byte("0123456789"))
	}))
	defer server.Close()
	previousTransport := http.DefaultTransport
	http.DefaultTransport = server.Client().Transport
	defer func() { http.DefaultTransport = previousTransport }()

	dst := filepath.Join(t.TempDir(), "small-artifact")
	written, err := DownloadFileWithLimit(server.URL, dst, time.Second, 5)
	if err == nil {
		t.Fatal("chunked body larger than the hard limit unexpectedly accepted")
	}
	if written != 6 {
		t.Fatalf("limited reader wrote %d bytes, want 6", written)
	}
}
