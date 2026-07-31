package backup

import (
	"strings"
	"testing"
)

func TestSafeTagRemovesPathSeparators(t *testing.T) {
	got := safeTag("rollback-../../target")
	if strings.ContainsAny(got, `/\\`) || strings.Contains(got, "..") {
		t.Fatalf("unsafe backup tag: %s", got)
	}
}
