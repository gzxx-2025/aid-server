package task

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
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
		t.Fatal("explicit-mode source builder was rejected")
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

func findEnvironmentValue(env []string, want string) string {
	for _, item := range env {
		key, value, found := strings.Cut(item, "=")
		if found && key == want {
			return value
		}
	}
	return ""
}
