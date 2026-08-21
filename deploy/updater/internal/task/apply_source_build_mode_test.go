package task

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"aid-updater/internal/manifest"
)

func TestSourceBuildModeForServiceManager(t *testing.T) {
	tests := []struct {
		name    string
		manager string
		want    string
		wantErr bool
	}{
		{name: "docker", manager: "docker", want: "docker"},
		{name: "systemd", manager: "systemd", want: "host"},
		{name: "normalizes case and whitespace", manager: " SYSTEMD ", want: "host"},
		{name: "rejects unknown manager", manager: "unknown", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := sourceBuildModeForServiceManager(tt.manager)
			if tt.wantErr {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatalf("sourceBuildModeForServiceManager() error = %v", err)
			}
			if got != tt.want {
				t.Fatalf("sourceBuildModeForServiceManager() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestSourceBuildEnvironmentOverridesManagedValues(t *testing.T) {
	env := sourceBuildEnvironment([]string{
		"PATH=/usr/bin",
		"AID_DATA_ROOT=/unsafe",
		"AID_SOURCE_BUILD_MODE=docker",
		"AID_DEPENDENCY_INSTALL_MODE=manual",
		"AID_DEPENDENCY_REGION=global",
		"AID_DOCKER_MIRRORS=stale.example",
	}, "/data/aid", "host", "auto", "cn", "mirror.example")
	want := map[string]string{
		"AID_DATA_ROOT":               "/data/aid",
		"AID_SOURCE_BUILD_MODE":       "host",
		"AID_DEPENDENCY_INSTALL_MODE": "auto",
		"AID_DEPENDENCY_REGION":       "cn",
	}
	got := make(map[string]string)
	for _, item := range env {
		key, value, found := strings.Cut(item, "=")
		if !found {
			continue
		}
		if _, exists := got[key]; exists {
			t.Fatalf("duplicate environment key %s", key)
		}
		got[key] = value
	}
	for key, value := range want {
		if got[key] != value {
			t.Fatalf("environment %s = %q, want %q", key, got[key], value)
		}
	}
	if _, exists := got["AID_DOCKER_MIRRORS"]; exists {
		t.Fatal("host source build must not pass Docker mirror settings")
	}

	dockerEnv := sourceBuildEnvironment([]string{"AID_DOCKER_MIRRORS=stale.example"}, "/data/aid", "docker", "auto", "cn", "mirror.example")
	if got := findEnvironmentValue(dockerEnv, "AID_DOCKER_MIRRORS"); got != "mirror.example" {
		t.Fatalf("docker mirror environment = %q, want mirror.example", got)
	}
}

func TestEnsureSourceBuildInterpreterUsesExistingBash(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousInstall := sourceBuildInstallDockerTools
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildInstallDockerTools = previousInstall
	})
	installCalls := 0
	sourceBuildLookPath = func(command string) (string, error) {
		return "/usr/bin/" + command, nil
	}
	sourceBuildInstallDockerTools = func(context.Context) error {
		installCalls++
		return nil
	}

	interpreter, err := ensureSourceBuildInterpreter(context.Background(), "docker")
	if err != nil {
		t.Fatalf("ensureSourceBuildInterpreter() error = %v", err)
	}
	if interpreter != "/usr/bin/bash" {
		t.Fatalf("interpreter = %q, want /usr/bin/bash", interpreter)
	}
	if installCalls != 0 {
		t.Fatalf("installer called %d times, want 0", installCalls)
	}
}

func TestEnsureSourceBuildInterpreterRepairsDockerContainer(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousInstall := sourceBuildInstallDockerTools
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildInstallDockerTools = previousInstall
	})
	installed := false
	installCalls := 0
	sourceBuildLookPath = func(command string) (string, error) {
		if command == "apk" || installed {
			return "/usr/bin/" + command, nil
		}
		return "", errors.New("not found")
	}
	sourceBuildInstallDockerTools = func(context.Context) error {
		installCalls++
		installed = true
		return nil
	}

	interpreter, err := ensureSourceBuildInterpreter(context.Background(), "docker")
	if err != nil {
		t.Fatalf("ensureSourceBuildInterpreter() error = %v", err)
	}
	if interpreter != "/usr/bin/bash" {
		t.Fatalf("interpreter = %q, want /usr/bin/bash", interpreter)
	}
	if installCalls != 1 {
		t.Fatalf("installer called %d times, want 1", installCalls)
	}
}

func TestEnsureSourceBuildInterpreterDoesNotMutateManualHost(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousInstall := sourceBuildInstallDockerTools
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildInstallDockerTools = previousInstall
	})
	installCalls := 0
	sourceBuildLookPath = func(string) (string, error) {
		return "", errors.New("not found")
	}
	sourceBuildInstallDockerTools = func(context.Context) error {
		installCalls++
		return nil
	}

	if _, err := ensureSourceBuildInterpreter(context.Background(), "systemd"); err == nil {
		t.Fatal("manual source build without Bash unexpectedly accepted")
	}
	if installCalls != 0 {
		t.Fatalf("manual path invoked Docker installer %d times", installCalls)
	}
}

func TestEnsureSourceBuildInterpreterReportsDockerRepairFailure(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousInstall := sourceBuildInstallDockerTools
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildInstallDockerTools = previousInstall
	})
	sourceBuildLookPath = func(command string) (string, error) {
		if command == "apk" {
			return "/sbin/apk", nil
		}
		return "", errors.New("not found")
	}
	sourceBuildInstallDockerTools = func(context.Context) error {
		return errors.New("mirror unavailable")
	}

	_, err := ensureSourceBuildInterpreter(context.Background(), "docker")
	if err == nil || !strings.Contains(err.Error(), "准备 Docker 源码构建工具失败") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestInstallDockerSourceBuildToolsFallsBackToDomesticMirror(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousRun := sourceBuildRun
	previousAlpineVersion := sourceBuildAlpineVersion
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildRun = previousRun
		sourceBuildAlpineVersion = previousAlpineVersion
	})
	installed := false
	sourceBuildLookPath = func(command string) (string, error) {
		if command == "apk" || installed {
			return "/sbin/apk", nil
		}
		return "", errors.New("not found")
	}
	sourceBuildAlpineVersion = func() string { return "v3.21" }
	var calls [][]string
	sourceBuildRun = func(ctx context.Context, name string, args ...string) error {
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) < 14*time.Minute {
			t.Fatalf("installer attempt deadline = %v, want approximately 15 minutes", deadline)
		}
		call := append([]string{name}, args...)
		calls = append(calls, call)
		if len(calls) == 1 {
			return errors.New("current mirror unavailable")
		}
		installed = true
		return nil
	}

	if err := installDockerSourceBuildTools(context.Background()); err != nil {
		t.Fatalf("installDockerSourceBuildTools() error = %v", err)
	}
	if len(calls) != 2 {
		t.Fatalf("installer calls = %d, want 2", len(calls))
	}
	second := strings.Join(calls[1], " ")
	for _, expected := range []string{
		"/sbin/apk add --no-cache",
		"https://mirrors.aliyun.com/alpine/v3.21/main",
		"https://mirrors.aliyun.com/alpine/v3.21/community",
	} {
		if !strings.Contains(second, expected) {
			t.Fatalf("fallback command %q does not contain %q", second, expected)
		}
	}
	for _, packageName := range dockerSourceBuildPackages {
		if !strings.Contains(" "+second+" ", " "+packageName+" ") {
			t.Fatalf("fallback command %q does not contain package %q", second, packageName)
		}
	}
}

func TestInstallDockerSourceBuildToolsAcceptsCompletedPartialInstall(t *testing.T) {
	previousLookPath := sourceBuildLookPath
	previousRun := sourceBuildRun
	previousAlpineVersion := sourceBuildAlpineVersion
	t.Cleanup(func() {
		sourceBuildLookPath = previousLookPath
		sourceBuildRun = previousRun
		sourceBuildAlpineVersion = previousAlpineVersion
	})
	installed := false
	sourceBuildLookPath = func(command string) (string, error) {
		if command == "apk" || installed {
			return "/usr/bin/" + command, nil
		}
		return "", errors.New("not found")
	}
	sourceBuildAlpineVersion = func() string { return "v3.21" }
	sourceBuildRun = func(context.Context, string, ...string) error {
		installed = true
		return context.DeadlineExceeded
	}

	if err := installDockerSourceBuildTools(context.Background()); err != nil {
		t.Fatalf("completed partial install was rejected: %v", err)
	}
}

func TestDigitsOnly(t *testing.T) {
	for _, value := range []string{"3", "21", "123"} {
		if !digitsOnly(value) {
			t.Fatalf("digitsOnly(%q) = false", value)
		}
	}
	for _, value := range []string{"", "3a", "v3", "2-1"} {
		if digitsOnly(value) {
			t.Fatalf("digitsOnly(%q) = true", value)
		}
	}
}

func TestSourceBuildScriptSupportsExplicitMode(t *testing.T) {
	dir := t.TempDir()
	legacy := filepath.Join(dir, "legacy.sh")
	if err := os.WriteFile(legacy, []byte("#!/bin/sh\nexit 0\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	supported, err := sourceBuildScriptSupportsExplicitMode(legacy)
	if err != nil {
		t.Fatal(err)
	}
	if supported {
		t.Fatal("legacy source builder unexpectedly accepted")
	}

	current := filepath.Join(dir, "current.sh")
	content := strings.Join([]string{
		"# " + sourceBuildModeCapability,
		"# " + sourceBuildGovernorCapability,
		"SOURCE_BUILD_MODE=\"${AID_SOURCE_BUILD_MODE:-auto}\"",
		"case \"$SOURCE_BUILD_MODE\" in",
	}, "\n")
	if err := os.WriteFile(current, []byte(content), 0o700); err != nil {
		t.Fatal(err)
	}
	supported, err = sourceBuildScriptSupportsExplicitMode(current)
	if err != nil {
		t.Fatal(err)
	}
	if !supported {
		t.Fatal("governed explicit-mode source builder was rejected")
	}

	missingGovernor := filepath.Join(dir, "missing-governor.sh")
	missingGovernorContent := strings.Join([]string{
		"# " + sourceBuildModeCapability,
		"SOURCE_BUILD_MODE=\"${AID_SOURCE_BUILD_MODE:-auto}\"",
		"case \"$SOURCE_BUILD_MODE\" in",
	}, "\n")
	if err := os.WriteFile(missingGovernor, []byte(missingGovernorContent), 0o700); err != nil {
		t.Fatal(err)
	}
	supported, err = sourceBuildScriptSupportsExplicitMode(missingGovernor)
	if err != nil {
		t.Fatal(err)
	}
	if supported {
		t.Fatal("source builder without resource governor unexpectedly accepted")
	}

	link := filepath.Join(dir, "builder-link.sh")
	if err := os.Symlink(current, link); err != nil {
		t.Logf("symlink unsupported in current test environment: %v", err)
	} else {
		supported, err = sourceBuildScriptSupportsExplicitMode(link)
		if err != nil {
			t.Fatal(err)
		}
		if supported {
			t.Fatal("symbolic-link source builder unexpectedly accepted")
		}
	}

	overSized := filepath.Join(dir, "oversized.sh")
	if err := os.WriteFile(overSized, []byte(strings.Repeat("x", maxSourceBuildScriptSize+1)), 0o700); err != nil {
		t.Fatal(err)
	}
	supported, err = sourceBuildScriptSupportsExplicitMode(overSized)
	if err != nil {
		t.Fatal(err)
	}
	if supported {
		t.Fatal("oversized source builder unexpectedly accepted")
	}
}

func TestPrepareTargetSourceBuilderDownloadsVerifiedGovernor(t *testing.T) {
	content := []byte(strings.Join([]string{
		"#!/bin/sh",
		"# " + sourceBuildModeCapability,
		"# " + sourceBuildGovernorCapability,
		"SOURCE_BUILD_MODE=\"${AID_SOURCE_BUILD_MODE:-auto}\"",
		"case \"$SOURCE_BUILD_MODE\" in",
	}, "\n"))
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write(content)
	}))
	defer server.Close()
	previousTransport := http.DefaultTransport
	http.DefaultTransport = server.Client().Transport
	defer func() { http.DefaultTransport = previousTransport }()

	digest := fmt.Sprintf("%x", sha256.Sum256(content))
	builder := &manifest.SourceBuilderArtifact{
		URL: server.URL, SHA256: digest, Capability: manifest.SourceBuilderCapability,
	}
	target := filepath.Join(t.TempDir(), "target-builder.sh")
	if err := prepareTargetSourceBuilder(builder, target); err != nil {
		t.Fatalf("prepareTargetSourceBuilder() error = %v", err)
	}
	actual, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(actual) != string(content) {
		t.Fatal("downloaded builder content changed")
	}
	info, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o700 {
		t.Fatalf("builder mode = %o, want 700", info.Mode().Perm())
	}

	badTarget := filepath.Join(t.TempDir(), "bad-builder.sh")
	builder.SHA256 = strings.Repeat("0", 64)
	if err := prepareTargetSourceBuilder(builder, badTarget); err == nil {
		t.Fatal("builder with a bad SHA256 unexpectedly accepted")
	}
	if _, err := os.Stat(badTarget); !os.IsNotExist(err) {
		t.Fatalf("bad builder final path must not exist, stat err = %v", err)
	}
}

func findEnvironmentValue(env []string, want string) string {
	for _, item := range env {
		key, value, found := strings.Cut(item, "=")
		if found && key == want {
			return value
		}
	}
	return ""
}
