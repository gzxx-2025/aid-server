package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRefreshDeploymentUsesRuntimeConfigAndHidesSecrets(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "aid-deploy.conf")
	raw := []byte("HTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=9090\n" +
		"DB_HOST=10.0.0.8\nDB_PORT=3307\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=db-secret\n" +
		"REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nREDIS_PASSWORD=redis-secret\n" +
		"TOKEN_SECRET=token-secret\nJAVA_OPTS=-Xmx2g\nROCKETMQ_ENABLED=false\n")
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		Install: Install{ServiceManager: "systemd", HealthCheckURL: "http://127.0.0.1:8080"},
		Deployment: Deployment{
			ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: filepath.Join(root, "config"),
			DescriptorFile:    filepath.Join(root, "config", "deployment.json"),
		},
	}
	state, err := cfg.RefreshDeployment()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Database.Host != "10.0.0.8" || cfg.Database.Port != 3307 || cfg.Database.Password != "db-secret" {
		t.Fatalf("database was not refreshed: %+v", cfg.Database)
	}
	if cfg.Install.HealthCheckURL != "http://127.0.0.1:9090" {
		t.Fatalf("health URL was not refreshed: %s", cfg.Install.HealthCheckURL)
	}
	if _, leaked := state.SafeValues["DB_PASSWORD"]; leaked {
		t.Fatal("database password leaked into safe values")
	}
	if len(state.ConfiguredSecrets) != 3 {
		t.Fatalf("unexpected configured secret list: %#v", state.ConfiguredSecrets)
	}
}

func TestBuildDeploymentConfigRestrictsPathAndPreservesComments(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(allowedRoot, "runtime.conf")
	raw := []byte("# keep this comment\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_HOST=127.0.0.1\nDB_PORT=3306\nDB_NAME=aid\nDB_USERNAME=root\nDB_PASSWORD=secret\n" +
		"REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nTOKEN_SECRET=secret\nROCKETMQ_ENABLED=false\n")
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		Install: Install{ServiceManager: "systemd"},
		Deployment: Deployment{
			ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: allowedRoot,
			DescriptorFile:    filepath.Join(allowedRoot, "deployment.json"),
		},
	}
	merged, _, err := cfg.BuildDeploymentConfig(configPath, map[string]string{"BACKEND_PORT": "9090"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(merged), "# keep this comment\n") {
		t.Fatalf("comment was not preserved: %q", string(merged))
	}
	outside := filepath.Join(root, "outside.conf")
	if _, _, err := cfg.BuildDeploymentConfig(outside, map[string]string{"BACKEND_PORT": "9090"}); err == nil {
		t.Fatal("outside path should be rejected")
	}
}
