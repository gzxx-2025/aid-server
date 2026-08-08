package task

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"

	"aid-updater/internal/config"
)

func TestValidateCertificatePairRejectsMismatchSanAndExpired(t *testing.T) {
	certificate, privateKey := makeCertificatePair(t, []string{"www.example.com", "admin.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	_, anotherKey := makeCertificatePair(t, []string{"www.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	if _, err := validateCertificatePair(certificate, anotherKey, "www.example.com"); err == nil {
		t.Fatal("mismatched private key should be rejected")
	}
	if _, err := validateCertificatePair(certificate, privateKey, "missing.example.com"); err == nil {
		t.Fatal("missing SAN should be rejected")
	}
	expiredCertificate, expiredKey := makeCertificatePair(t, []string{"www.example.com"}, time.Now().Add(-48*time.Hour), time.Now().Add(-24*time.Hour))
	if _, err := validateCertificatePair(expiredCertificate, expiredKey, "www.example.com"); err == nil {
		t.Fatal("expired certificate should be rejected")
	}
}

func TestValidateCertificatePairRejectsBrokenFullchain(t *testing.T) {
	leafCertificate, leafKey := makeCertificatePair(t, []string{"www.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	unrelatedIssuer, _ := makeCertificatePair(t, []string{"issuer.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	brokenFullchain := append(append([]byte{}, leafCertificate...), unrelatedIssuer...)
	if _, err := validateCertificatePair(brokenFullchain, leafKey, "www.example.com"); err == nil {
		t.Fatal("a fullchain with an unrelated issuer must be rejected")
	}
}

func TestValidateCertificatePairRejectsClientOnlyCertificate(t *testing.T) {
	certificate, key := makeCertificatePairWithUsage(t, []string{"www.example.com"},
		time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour), []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth})
	if _, err := validateCertificatePair(certificate, key, "www.example.com"); err == nil {
		t.Fatal("a client-auth-only certificate must not be accepted for HTTPS")
	}
}

func TestCertificateInstallRejectsEmptyDomains(t *testing.T) {
	if err := validateCertificateInstallDomains("systemd", map[string]string{}); err == nil {
		t.Fatal("certificate installation must reject empty public and admin domains")
	}
}

func TestCertificateInstallRejectsInvalidOrEquivalentDomains(t *testing.T) {
	invalidPairs := [][2]string{
		{"a..example.com", "admin.example.com"},
		{"a-.example.com", "admin.example.com"},
		{"WWW.Example.com", "www.example.com"},
	}
	for _, pair := range invalidPairs {
		values := map[string]string{"HTTPS_PUBLIC_DOMAIN": pair[0], "HTTPS_ADMIN_DOMAIN": pair[1]}
		if err := validateCertificateInstallDomains("systemd", values); err == nil {
			t.Fatalf("invalid or equivalent domain pair should be rejected: %v", pair)
		}
	}
}

func TestResolveCertificateStagingRejectsTraversalAndSymlink(t *testing.T) {
	root := t.TempDir()
	runner := &Runner{cfg: &config.Config{TaskFile: filepath.Join(root, "inbox", "task.json")}}
	if _, err := runner.resolveCertificateStagingFile(filepath.Join(root, "outside.tmp")); err == nil {
		t.Fatal("path traversal should be rejected")
	}
	staging := filepath.Join(root, "inbox", "cert-staging")
	if err := os.MkdirAll(staging, 0o700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(staging, "target.tmp")
	if err := os.WriteFile(target, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(staging, "link.tmp")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := runner.resolveCertificateStagingFile(link); err == nil {
		t.Fatal("symlink should be rejected")
	}
}

func TestCertificateInstallRestoresExistingPairWhenConfigValidationFails(t *testing.T) {
	root := t.TempDir()
	sslRoot := filepath.Join(root, "config", "ssl")
	staging := filepath.Join(root, "inbox", "cert-staging")
	if err := os.MkdirAll(sslRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(staging, 0o700); err != nil {
		t.Fatal(err)
	}
	oldCertificate, oldKey := makeCertificatePair(t, []string{"www.example.com", "admin.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(24*time.Hour))
	newCertificate, newKey := makeCertificatePair(t, []string{"www.example.com", "admin.example.com"}, time.Now().Add(-time.Hour), time.Now().Add(48*time.Hour))
	certificateTarget := filepath.Join(sslRoot, "fullchain.pem")
	keyTarget := filepath.Join(sslRoot, "privkey.pem")
	if err := os.WriteFile(certificateTarget, oldCertificate, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyTarget, oldKey, 0o600); err != nil {
		t.Fatal(err)
	}
	certificateStaging := filepath.Join(staging, "certificate.tmp")
	keyStaging := filepath.Join(staging, "private-key.tmp")
	if err := os.WriteFile(certificateStaging, newCertificate, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyStaging, newKey, 0o600); err != nil {
		t.Fatal(err)
	}
	configPath := filepath.Join(root, "config", "aid-deploy.conf")
	configuration := "DATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_HOST=127.0.0.1\nDB_PORT=3306\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=secret\n" +
		"REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nTOKEN_SECRET=secret\nROCKETMQ_ENABLED=false\n" +
		"HTTPS_ENABLED=true\nHTTPS_PORT=443\nHTTPS_PUBLIC_DOMAIN=www.example.com\nHTTPS_ADMIN_DOMAIN=admin.example.com\n" +
		"HTTPS_CERT_PATH=" + certificateTarget + "\nHTTPS_KEY_PATH=" + keyTarget + "\n"
	if err := os.WriteFile(configPath, []byte(configuration), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{
		TaskFile: filepath.Join(root, "inbox", "task.json"), BackupDir: filepath.Join(root, "backups"),
		Install: config.Install{ServiceManager: "systemd"},
		Deployment: config.Deployment{ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: filepath.Join(root, "config"), DescriptorFile: filepath.Join(root, "config", "deployment.json")},
	}
	runner := &Runner{cfg: cfg}
	task := &Task{CertificateFile: certificateStaging, PrivateKeyFile: keyStaging,
		ConfigPath:   filepath.Join(root, "outside.env"),
		ConfigValues: map[string]string{"HTTPS_PUBLIC_DOMAIN": "www.example.com", "HTTPS_ADMIN_DOMAIN": "admin.example.com"}}
	if err := runner.runCertificateInstall(task); err == nil {
		t.Fatal("invalid candidate configuration should fail")
	}
	actualCertificate, _ := os.ReadFile(certificateTarget)
	actualKey, _ := os.ReadFile(keyTarget)
	if string(actualCertificate) != string(oldCertificate) || string(actualKey) != string(oldKey) {
		t.Fatal("existing certificate pair was not restored")
	}
}

func TestPersistCertificateBackupPrunesOldManagedDirectories(t *testing.T) {
	root := t.TempDir()
	backupRoot := filepath.Join(root, "configuration", "certificates")
	if err := os.MkdirAll(backupRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	oldNames := []string{
		"20010101-000000.000000001",
		"20020101-000000.000000001",
		"20030101-000000.000000001",
		"20040101-000000.000000001",
	}
	for _, name := range oldNames {
		directory := filepath.Join(backupRoot, name)
		if err := os.Mkdir(directory, 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(directory, "privkey.pem"), []byte("old-key"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	unmanaged := filepath.Join(backupRoot, "manual-backup")
	if err := os.Mkdir(unmanaged, 0o700); err != nil {
		t.Fatal(err)
	}
	runner := &Runner{cfg: &config.Config{BackupDir: root, KeepBackups: 2}}
	snapshot := certificateSnapshot{exists: true, content: []byte("current-key")}
	if err := runner.persistCertificateBackup(certificateSnapshot{}, snapshot); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(backupRoot)
	if err != nil {
		t.Fatal(err)
	}
	managedNames := make([]string, 0)
	for _, entry := range entries {
		if _, err := time.Parse(certificateBackupTimestampLayout, entry.Name()); err == nil && entry.IsDir() {
			managedNames = append(managedNames, entry.Name())
		}
	}
	if len(managedNames) != 2 {
		t.Fatalf("expected two retained managed certificate backups, got %v", managedNames)
	}
	if _, err := os.Stat(filepath.Join(backupRoot, oldNames[3])); err != nil {
		t.Fatalf("latest historical backup should be retained: %v", err)
	}
	if _, err := os.Stat(unmanaged); err != nil {
		t.Fatalf("unmanaged directory must not be deleted: %v", err)
	}
}

func TestPruneCertificateBackupsUsesDefaultAndDoesNotFollowSymlink(t *testing.T) {
	backupRoot := t.TempDir()
	for index := 1; index <= 4; index++ {
		name := fmt.Sprintf("200%d0101-000000.000000001", index)
		if err := os.Mkdir(filepath.Join(backupRoot, name), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	external := t.TempDir()
	sentinel := filepath.Join(external, "privkey.pem")
	if err := os.WriteFile(sentinel, []byte("must-remain"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(backupRoot, "19990101-000000.000000001")
	if err := os.Symlink(external, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if err := pruneCertificateBackups(backupRoot, 0); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(backupRoot)
	if err != nil {
		t.Fatal(err)
	}
	managedCount := 0
	for _, entry := range entries {
		if entry.Type()&os.ModeSymlink == 0 {
			if _, err := time.Parse(certificateBackupTimestampLayout, entry.Name()); err == nil && entry.IsDir() {
				managedCount++
			}
		}
	}
	if managedCount != 3 {
		t.Fatalf("default retention should keep three managed backups, got %d", managedCount)
	}
	if content, err := os.ReadFile(sentinel); err != nil || string(content) != "must-remain" {
		t.Fatalf("symlink target must remain untouched: %v", err)
	}
}

func TestDisabledOptionalDiagnosticsAreSkipped(t *testing.T) {
	state := &config.DeploymentState{Mode: "systemd", Values: map[string]string{"HTTPS_ENABLED": "false"}}
	if result := testHTTPS(state); result.Status != "SKIPPED" {
		t.Fatalf("disabled HTTPS should be skipped: %+v", result)
	}
	if result := testRocketMQ(map[string]string{"ROCKETMQ_ENABLED": "false"}); result.Status != "SKIPPED" {
		t.Fatalf("disabled RocketMQ should be skipped: %+v", result)
	}
	emptyCertificate := map[string]string{
		"HTTPS_PUBLIC_DOMAIN": "www.example.com",
		"HTTPS_ADMIN_DOMAIN":  "admin.example.com",
	}
	if err := config.ValidateDeploymentDiagnostic("certificate", &config.DeploymentState{Mode: "systemd", Values: emptyCertificate}); err != nil {
		t.Fatalf("empty certificate pair should reach the skipped diagnostic result: %v", err)
	}
	if result := testCertificate(emptyCertificate); result.Status != "SKIPPED" {
		t.Fatalf("an unconfigured certificate pair should be skipped: %+v", result)
	}
}

func TestDNSDiagnosticCandidateIgnoresMissingHTTPSCertificate(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "config", "aid-deploy.conf")
	if err := os.MkdirAll(filepath.Dir(configPath), 0o700); err != nil {
		t.Fatal(err)
	}
	configuration := "DATA_ROOT=" + root + "\nHTTP_PORT=80\nADMIN_PORT=8090\nBACKEND_PORT=8080\n" +
		"DB_HOST=127.0.0.1\nDB_PORT=3306\nDB_NAME=aid\nDB_USERNAME=aid\nDB_PASSWORD=secret\n" +
		"REDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nTOKEN_SECRET=secret\nROCKETMQ_ENABLED=false\n" +
		"HTTPS_ENABLED=false\nHTTPS_PORT=443\n"
	if err := os.WriteFile(configPath, []byte(configuration), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{
		Install: config.Install{ServiceManager: "systemd"},
		Deployment: config.Deployment{ConfigPath: configPath, DefaultConfigPath: configPath,
			AllowedConfigRoot: filepath.Dir(configPath), DescriptorFile: filepath.Join(root, "config", "deployment.json")},
	}
	runner := &Runner{cfg: cfg}
	task := &Task{ConfigValues: map[string]string{
		"HTTPS_ENABLED":       "true",
		"HTTPS_PUBLIC_DOMAIN": "www.example.com",
		"HTTPS_ADMIN_DOMAIN":  "admin.example.com",
		"HTTPS_CERT_PATH":     filepath.Join(root, "config", "ssl", "missing.pem"),
		"HTTPS_KEY_PATH":      filepath.Join(root, "config", "ssl", "missing-key.pem"),
	}}
	state, err := runner.buildDiagnosticState(task, "dns")
	if err != nil {
		t.Fatalf("DNS candidate must not be blocked by an unrelated missing certificate: %v", err)
	}
	if err := config.ValidateDeploymentDiagnostic("dns", state); err != nil {
		t.Fatalf("DNS-only validation should accept the completed domain fields: %v", err)
	}
	if state.Values["HTTPS_ENABLED"] != "false" || state.Values["HTTPS_CERT_PATH"] != "" {
		t.Fatal("DNS candidate must not merge unrelated HTTPS enablement or certificate paths")
	}
}

func TestUniqueIPAddressesReturnsFullDeduplicatedAddresses(t *testing.T) {
	actual := uniqueIPAddresses([]string{"43.226.47.134", "43.226.47.134", "2001:db8::1"})
	if actual != "43.226.47.134, 2001:db8::1" {
		t.Fatalf("unexpected DNS diagnostic addresses: %s", actual)
	}
}

func makeCertificatePair(t *testing.T, domains []string, notBefore, notAfter time.Time) ([]byte, []byte) {
	return makeCertificatePairWithUsage(t, domains, notBefore, notAfter, nil)
}

func makeCertificatePairWithUsage(t *testing.T, domains []string, notBefore, notAfter time.Time,
	extKeyUsage []x509.ExtKeyUsage) ([]byte, []byte) {
	t.Helper()
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: domains[0]},
		DNSNames: domains, NotBefore: notBefore, NotAfter: notAfter, KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: extKeyUsage,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &privateKey.PublicKey, privateKey)
	if err != nil {
		t.Fatal(err)
	}
	certificate := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	key := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(privateKey)})
	return certificate, key
}
