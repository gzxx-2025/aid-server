package task

import (
	"os"
	"path/filepath"
	"testing"

	"aid-updater/internal/config"
)

func TestRefreshDeploymentAssetsPreservesDockerEnv(t *testing.T) {
	root := t.TempDir()
	packageRoot := filepath.Join(root, "package")
	sourceDeploy := filepath.Join(packageRoot, "installer", "deploy")
	targetDeploy := filepath.Join(root, "installed", "deploy")
	if err := os.MkdirAll(filepath.Join(sourceDeploy, "docker", "nginx"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(targetDeploy, "docker"), 0o755); err != nil {
		t.Fatal(err)
	}
	files := map[string]string{
		filepath.Join(sourceDeploy, "build-release-from-source.sh"): "#!/bin/sh\n",
		filepath.Join(sourceDeploy, "aid.sh"):                       "#!/bin/bash\n",
		filepath.Join(sourceDeploy, "docker", "docker-compose.yml"): "services: {}\n",
		filepath.Join(sourceDeploy, "docker", ".env.example"):       "DEPENDENCY_REGION=auto\n",
		filepath.Join(sourceDeploy, "docker", ".env"):               "DB_PASSWORD=package-secret\n",
		filepath.Join(sourceDeploy, "docker", "nginx", "aid.conf"):  "server {}\n",
		filepath.Join(targetDeploy, "docker", "docker-compose.yml"): "old\n",
		filepath.Join(targetDeploy, "docker", ".env"):               "DB_PASSWORD=user-secret\n",
	}
	for path, content := range files {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	runner := &Runner{cfg: &config.Config{
		SourceBuildScript: filepath.Join(targetDeploy, "build-release-from-source.sh"),
	}}
	refreshed, err := runner.refreshDeploymentAssets(packageRoot)
	if err != nil {
		t.Fatal(err)
	}
	if !refreshed {
		t.Fatal("deployment assets should be refreshed")
	}
	userEnv, err := os.ReadFile(filepath.Join(targetDeploy, "docker", ".env"))
	if err != nil {
		t.Fatal(err)
	}
	if string(userEnv) != "DB_PASSWORD=user-secret\n" {
		t.Fatalf("user .env was overwritten: %q", userEnv)
	}
	compose, err := os.ReadFile(filepath.Join(targetDeploy, "docker", "docker-compose.yml"))
	if err != nil {
		t.Fatal(err)
	}
	if string(compose) != "services: {}\n" {
		t.Fatalf("compose file was not refreshed: %q", compose)
	}
}
