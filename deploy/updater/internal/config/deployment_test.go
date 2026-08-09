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
	raw := []byte("DATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=9090\n" +
		"DB_HOST=10.0.0.8\nDB_PORT=3307\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=db-secret\n" +
		"REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nREDIS_USERNAME=acl-user\nREDIS_PASSWORD=redis-secret\n" +
		"TOKEN_SECRET=token-secret\nJAVA_OPTS=-Xmx2g\nDEPENDENCY_INSTALL_MODE=manual\nDEPENDENCY_REGION=cn\n" +
		"ROCKETMQ_ENABLED=true\nROCKETMQ_NAMESERVER=10.0.0.9:9876\nROCKETMQ_FLUSH_DISK_TYPE=SYNC_FLUSH\n" +
		"ROCKETMQ_ACCESS_KEY=mqaccess\nROCKETMQ_SECRET_KEY=mqsecret\n")
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
	if len(state.ConfiguredSecrets) != 5 {
		t.Fatalf("unexpected configured secret list: %#v", state.ConfiguredSecrets)
	}
	if _, leaked := state.SafeValues["ROCKETMQ_ACCESS_KEY"]; leaked {
		t.Fatal("RocketMQ access key leaked into safe values")
	}
	if state.SafeValues["DEPENDENCY_INSTALL_MODE"] != "manual" || state.SafeValues["DEPENDENCY_REGION"] != "cn" || state.SafeValues["ROCKETMQ_FLUSH_DISK_TYPE"] != "SYNC_FLUSH" {
		t.Fatalf("dependency or RocketMQ flush mode was not retained: %#v", state.SafeValues)
	}
}

func TestRefreshDockerExternalMySQLUsesEphemeralClient(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(allowedRoot, "docker.env")
	raw := []byte("DATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_HOST=db.internal\nDB_PORT=3307\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=db-secret\n" +
		"REDIS_HOST=redis\nREDIS_PORT=6379\nTOKEN_SECRET=token-secret\n" +
		"COMPOSE_PROFILES=redis\nROCKETMQ_ENABLED=false\n")
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		Install: Install{ServiceManager: "docker", HealthCheckURL: "http://aid-server:8080"},
		Deployment: Deployment{
			ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: allowedRoot,
			DescriptorFile:    filepath.Join(allowedRoot, "deployment.json"),
		},
	}
	if _, err := cfg.RefreshDeployment(); err != nil {
		t.Fatal(err)
	}
	if cfg.Database.ExecContainer != "" || cfg.Database.ClientImage != "mysql:5.7" ||
		cfg.Database.DockerNetwork != "host" {
		t.Fatalf("external MySQL client mode was not selected: %+v", cfg.Database)
	}
	if cfg.Database.Host != "db.internal" || cfg.Database.Port != 3307 || cfg.Database.User != "aid" {
		t.Fatalf("external MySQL connection was not loaded: %+v", cfg.Database)
	}
}

func TestRefreshLegacyDockerConfigKeepsInternalMySQL(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(allowedRoot, "docker.env")
	raw := []byte("DATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=db-secret\nMYSQL_ROOT_PASSWORD=root-secret\nMYSQL_PORT=3306\n" +
		"REDIS_HOST=redis\nREDIS_PORT=6379\nTOKEN_SECRET=token-secret\n" +
		"COMPOSE_PROFILES=redis\nROCKETMQ_ENABLED=false\n")
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{
		Install: Install{ServiceManager: "docker", HealthCheckURL: "http://aid-server:8080"},
		Deployment: Deployment{
			ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: allowedRoot,
			DescriptorFile:    filepath.Join(allowedRoot, "deployment.json"),
		},
	}
	state, err := cfg.RefreshDeployment()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Database.ExecContainer != "aid-mysql" || cfg.Database.ClientImage != "" {
		t.Fatalf("legacy Docker config was not kept on internal MySQL: %+v", cfg.Database)
	}
	if !strings.Contains(state.Values["COMPOSE_PROFILES"], "mysql") || state.Values["DB_HOST"] != "mysql" {
		t.Fatalf("legacy internal MySQL values were not normalized: %#v", state.Values)
	}
}

func TestBuildDeploymentConfigRestrictsPathAndPreservesComments(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(allowedRoot, "runtime.conf")
	raw := []byte("# keep this comment\nDATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
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

func TestReadDeploymentStateRejectsDataRootMismatch(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(allowedRoot, "docker.env")
	raw := []byte("DATA_ROOT=" + filepath.Join(root, "other") + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_HOST=mysql\nDB_PORT=3306\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=dbsecret\n" +
		"MYSQL_ROOT_PASSWORD=rootsecret\nMYSQL_PORT=3306\nREDIS_HOST=redis\nREDIS_PORT=6379\n" +
		"TOKEN_SECRET=tokensecret\nCOMPOSE_PROFILES=mysql,redis\nROCKETMQ_ENABLED=false\n")
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &Config{Install: Install{ServiceManager: "docker"}, Deployment: Deployment{
		ConfigPath: configPath, DefaultConfigPath: configPath,
		AllowedConfigRoot: allowedRoot, DescriptorFile: filepath.Join(allowedRoot, "deployment.json"),
	}}
	if _, err := cfg.ReadDeploymentState(); err == nil {
		t.Fatal("DATA_ROOT mismatch must be rejected")
	}
}

func TestValidateDeploymentDataRootRejectsNonCanonicalPathAndFilesystemRoot(t *testing.T) {
	root := t.TempDir()
	cfg := &Config{Deployment: Deployment{AllowedConfigRoot: filepath.Join(root, "config")}}
	nonCanonical := filepath.Join(root, "nested") + string(os.PathSeparator) + ".."
	if err := cfg.validateDeploymentDataRoot(map[string]string{"DATA_ROOT": nonCanonical}); err == nil {
		t.Fatal("DATA_ROOT containing .. must be rejected")
	}
	if err := cfg.validateDeploymentDataRoot(map[string]string{"DATA_ROOT": root + string(os.PathSeparator)}); err != nil {
		t.Fatalf("a single trailing separator should be normalized: %v", err)
	}

	filesystemRoot := filepath.Clean(filepath.VolumeName(root) + string(os.PathSeparator))
	cfg.Deployment.AllowedConfigRoot = filepath.Join(filesystemRoot, "config")
	if err := cfg.validateDeploymentDataRoot(map[string]string{"DATA_ROOT": filesystemRoot}); err == nil {
		t.Fatal("filesystem root must not become the updater data root")
	}
}

func TestReadDeploymentStateRejectsSymlinkConfigPath(t *testing.T) {
	root := t.TempDir()
	allowedRoot := filepath.Join(root, "config")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	realPath := filepath.Join(allowedRoot, "real.env")
	linkPath := filepath.Join(allowedRoot, "runtime.env")
	if err := os.WriteFile(realPath, []byte("DATA_ROOT="+root+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(realPath, linkPath); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	cfg := &Config{Install: Install{ServiceManager: "docker"}, Deployment: Deployment{
		ConfigPath: linkPath, DefaultConfigPath: filepath.Join(allowedRoot, "docker.env"),
		AllowedConfigRoot: allowedRoot, DescriptorFile: filepath.Join(allowedRoot, "deployment.json"),
	}}
	if _, err := cfg.ReadDeploymentState(); err == nil {
		t.Fatal("symlink deployment config must be rejected")
	}
}

func TestValidateDockerExternalServicesAndHTTPS(t *testing.T) {
	root := t.TempDir()
	sslRoot := filepath.Join(root, "config", "ssl")
	if err := os.MkdirAll(sslRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	certPath := filepath.Join(sslRoot, "fullchain.pem")
	keyPath := filepath.Join(sslRoot, "privkey.pem")
	if err := os.WriteFile(certPath, []byte("certificate"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, []byte("private-key"), 0o600); err != nil {
		t.Fatal(err)
	}
	values := map[string]string{
		"HTTP_PORT": "80", "ADMIN_PORT": "8090", "BACKEND_PORT": "8080",
		"DB_HOST": "mysql.internal", "DB_PORT": "3306", "DB_NAME": "aid",
		"DB_USERNAME": "aid", "DB_PASSWORD": "db-secret",
		"REDIS_HOST": "redis.internal", "REDIS_PORT": "6379", "REDIS_USERNAME": "aid",
		"REDIS_PASSWORD": "", "REDIS_DATABASE": "1", "TOKEN_SECRET": "token-secret",
		"DATA_ROOT": root, "MYSQL_ROOT_PASSWORD": "mysql-secret", "MYSQL_PORT": "3306",
		"COMPOSE_PROFILES": "https", "HTTPS_PORT": "443",
		"HTTPS_PUBLIC_DOMAIN": "www.example.com", "HTTPS_ADMIN_DOMAIN": "admin.example.com",
		"HTTPS_CERT_PATH": certPath, "HTTPS_KEY_PATH": keyPath,
		"ROCKETMQ_ENABLED": "true", "ROCKETMQ_NAMESERVER": "mq.internal:9876",
		"ROCKETMQ_ACCESS_KEY": "access", "ROCKETMQ_SECRET_KEY": "secret",
	}
	if err := validateDeploymentValues("docker", values); err != nil {
		t.Fatalf("valid external-service HTTPS config was rejected: %v", err)
	}
	values["ROCKETMQ_NAMESERVER"] = "127.0.0.1:9876"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("Docker external RocketMQ loopback address should be rejected")
	}
	values["ROCKETMQ_NAMESERVER"] = "host.docker.internal:9876"
	if err := validateDeploymentValues("docker", values); err != nil {
		t.Fatalf("Docker host RocketMQ alias was rejected: %v", err)
	}
	values["ROCKETMQ_SECRET_KEY"] = ""
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("incomplete RocketMQ ACL credentials should be rejected")
	}
	values["ROCKETMQ_SECRET_KEY"] = "invalid-secret"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("RocketMQ ACL credentials with punctuation should be rejected")
	}
}

func TestValidateDockerInternalMySQLProfile(t *testing.T) {
	values := map[string]string{
		"DATA_ROOT": "/data/aid", "HTTP_PORT": "80", "ADMIN_PORT": "8090", "BACKEND_PORT": "8080",
		"DB_HOST": "mysql", "DB_PORT": "3306", "DB_NAME": "aid", "DB_USERNAME": "aid",
		"DB_PASSWORD": "dbsecret", "MYSQL_ROOT_PASSWORD": "rootsecret", "MYSQL_PORT": "3306",
		"REDIS_HOST": "redis", "REDIS_PORT": "6379", "TOKEN_SECRET": "tokensecret",
		"COMPOSE_PROFILES": "mysql,redis", "ROCKETMQ_ENABLED": "false",
	}
	if err := validateDeploymentValues("docker", values); err != nil {
		t.Fatalf("valid internal MySQL config was rejected: %v", err)
	}
	values["DB_HOST"] = "db.internal"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("internal MySQL profile with external host should be rejected")
	}
}

func TestValidateDependencyAndRocketMQFlushModes(t *testing.T) {
	values := map[string]string{
		"DATA_ROOT": "/data/aid", "HTTP_PORT": "80", "ADMIN_PORT": "8090", "BACKEND_PORT": "8080",
		"DB_HOST": "mysql", "DB_PORT": "3306", "DB_NAME": "aid", "DB_USERNAME": "aid",
		"DB_PASSWORD": "dbsecret", "MYSQL_ROOT_PASSWORD": "rootsecret", "MYSQL_PORT": "3306",
		"REDIS_HOST": "redis", "REDIS_PORT": "6379", "TOKEN_SECRET": "tokensecret",
		"COMPOSE_PROFILES": "mysql,redis", "ROCKETMQ_ENABLED": "false",
		"DEPENDENCY_INSTALL_MODE": "manual", "DEPENDENCY_REGION": "cn", "ROCKETMQ_FLUSH_DISK_TYPE": "SYNC_FLUSH",
		"DOCKER_MIRRORS": "docker.m.daocloud.io,dockerproxy.net,registry.example.com/team",
	}
	if err := validateDeploymentValues("docker", values); err != nil {
		t.Fatalf("valid dependency and flush modes were rejected: %v", err)
	}
	values["DEPENDENCY_INSTALL_MODE"] = "always"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("invalid dependency install mode should be rejected")
	}
	values["DEPENDENCY_INSTALL_MODE"] = "auto"
	values["DEPENDENCY_REGION"] = "internal"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("invalid dependency region should be rejected")
	}
	values["DEPENDENCY_REGION"] = "global"
	values["DOCKER_MIRRORS"] = "https://registry.example.com,mirror.example.com:5000/team"
	if err := validateDeploymentValues("docker", values); err != nil {
		t.Fatalf("valid Docker mirror list was rejected: %v", err)
	}
	values["DOCKER_MIRRORS"] = "registry.example.com/team?token=secret"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("Docker mirror query credentials should be rejected")
	}
	values["DOCKER_MIRRORS"] = "docker.m.daocloud.io,dockerproxy.net"
	values["ROCKETMQ_FLUSH_DISK_TYPE"] = "MEMORY_ONLY"
	if err := validateDeploymentValues("docker", values); err == nil {
		t.Fatal("invalid RocketMQ flush mode should be rejected")
	}
}

func TestValidateManualHTTPS(t *testing.T) {
	root := t.TempDir()
	sslRoot := filepath.Join(root, "config", "ssl")
	if err := os.MkdirAll(sslRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	certPath := filepath.Join(sslRoot, "fullchain.pem")
	keyPath := filepath.Join(sslRoot, "privkey.pem")
	if err := os.WriteFile(certPath, []byte("certificate"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, []byte("private-key"), 0o600); err != nil {
		t.Fatal(err)
	}
	values := map[string]string{
		"DATA_ROOT": root, "HTTP_PORT": "80", "ADMIN_PORT": "8090", "BACKEND_PORT": "8080",
		"DB_HOST": "127.0.0.1", "DB_PORT": "3306", "DB_NAME": "aid",
		"DB_USERNAME": "aid", "DB_PASSWORD": "dbsecret", "TOKEN_SECRET": "tokensecret",
		"REDIS_HOST": "127.0.0.1", "REDIS_PORT": "6379", "ROCKETMQ_ENABLED": "false",
		"HTTPS_ENABLED": "true", "HTTPS_PORT": "443",
		"HTTPS_PUBLIC_DOMAIN": "www.example.com", "HTTPS_ADMIN_DOMAIN": "admin.example.com",
		"HTTPS_CERT_PATH": certPath, "HTTPS_KEY_PATH": keyPath,
	}
	if err := validateDeploymentValues("systemd", values); err != nil {
		t.Fatalf("valid manual HTTPS config was rejected: %v", err)
	}
	values["HTTPS_CERT_PATH"] = filepath.Join(root, "outside.pem")
	if err := validateDeploymentValues("systemd", values); err == nil {
		t.Fatal("manual HTTPS certificate outside DATA_ROOT/config/ssl should be rejected")
	}
}

func TestValidateHTTPSDiagnosticRejectsInvalidPort(t *testing.T) {
	state := &DeploymentState{Mode: "systemd", Values: map[string]string{
		"HTTPS_ENABLED":       "true",
		"HTTPS_PORT":          "not-a-port",
		"HTTPS_PUBLIC_DOMAIN": "www.example.com",
		"HTTPS_ADMIN_DOMAIN":  "admin.example.com",
	}}
	if err := ValidateDeploymentDiagnostic("https", state); err == nil {
		t.Fatal("HTTPS diagnostic should reject a non-numeric port before network probing")
	}
}
