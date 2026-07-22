package artifact

import (
	"archive/tar"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeArchive(t *testing.T, header *tar.Header, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.tar.gz")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	gz := gzip.NewWriter(f)
	tw := tar.NewWriter(gz)
	if err := tw.WriteHeader(header); err != nil {
		t.Fatal(err)
	}
	if body != "" {
		if _, err := tw.Write([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestExtractTarGzRejectsTraversal(t *testing.T) {
	src := writeArchive(t, &tar.Header{Name: "../outside", Mode: 0o644, Size: 1, Typeflag: tar.TypeReg}, "x")
	err := ExtractTarGz(src, filepath.Join(t.TempDir(), "dst"))
	if err == nil || !strings.Contains(err.Error(), "非法路径") {
		t.Fatalf("expected traversal error, got %v", err)
	}
}

func TestExtractTarGzRejectsExpandedSize(t *testing.T) {
	if !exceedsExtractLimit(maxExtractedBytes+1, 0) {
		t.Fatal("expected expanded size to be rejected")
	}
	if !exceedsExtractLimit(2, maxExtractedBytes-1) {
		t.Fatal("expected cumulative expanded size to be rejected")
	}
}

func TestSecureURL(t *testing.T) {
	for _, raw := range []string{"http://example.com/a", "https://user:pass@example.com/a", "https:///a"} {
		if isSecureURL(raw) {
			t.Fatalf("expected URL to be rejected: %s", raw)
		}
	}
	if !isSecureURL("https://example.com/a") {
		t.Fatal("expected HTTPS URL to be accepted")
	}
}
