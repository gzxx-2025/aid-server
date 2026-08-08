package task

import (
	"bytes"
	"crypto"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"aid-updater/internal/config"
)

const maxCertificateUploadBytes = 1024 * 1024

type certificateSnapshot struct {
	path    string
	content []byte
	exists  bool
}

func (r *Runner) runCertificateInstall(t *Task) error {
	defer r.cleanupCertificateStaging(t.CertificateFile)
	defer r.cleanupCertificateStaging(t.PrivateKeyFile)
	certificatePath, err := r.resolveCertificateStagingFile(t.CertificateFile)
	if err != nil {
		return fmt.Errorf("证书暂存文件无效: %w", err)
	}
	privateKeyPath, err := r.resolveCertificateStagingFile(t.PrivateKeyFile)
	if err != nil {
		return fmt.Errorf("私钥暂存文件无效: %w", err)
	}

	certificatePEM, err := readLimitedRegularFile(certificatePath)
	if err != nil {
		return fmt.Errorf("读取证书失败: %w", err)
	}
	privateKeyPEM, err := readLimitedRegularFile(privateKeyPath)
	if err != nil {
		return fmt.Errorf("读取私钥失败: %w", err)
	}
	defer clear(privateKeyPEM)
	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return err
	}
	values := cloneStringMap(state.Values)
	for key, value := range t.ConfigValues {
		if key == "HTTPS_PUBLIC_DOMAIN" || key == "HTTPS_ADMIN_DOMAIN" {
			values[key] = strings.TrimSpace(value)
		}
	}
	if err := validateCertificateInstallDomains(state.Mode, values); err != nil {
		return err
	}
	certificate, err := validateCertificatePair(certificatePEM, privateKeyPEM,
		values["HTTPS_PUBLIC_DOMAIN"], values["HTTPS_ADMIN_DOMAIN"])
	if err != nil {
		return err
	}

	dataRoot := filepath.Clean(strings.TrimSpace(values["DATA_ROOT"]))
	if !filepath.IsAbs(dataRoot) {
		return fmt.Errorf("数据根目录无效")
	}
	sslRoot := filepath.Join(dataRoot, "config", "ssl")
	if err := ensureSecureDirectory(sslRoot); err != nil {
		return fmt.Errorf("证书目录无效: %w", err)
	}
	targetCertificate := filepath.Join(sslRoot, "fullchain.pem")
	targetPrivateKey := filepath.Join(sslRoot, "privkey.pem")
	certificateBackup, err := snapshotFile(targetCertificate)
	if err != nil {
		return err
	}
	privateKeyBackup, err := snapshotFile(targetPrivateKey)
	if err != nil {
		return err
	}
	defer clear(privateKeyBackup.content)
	configBackup, err := r.backupDeploymentConfiguration()
	if err != nil {
		return fmt.Errorf("备份当前配置失败: %w", err)
	}
	if err := r.persistCertificateBackup(certificateBackup, privateKeyBackup); err != nil {
		return fmt.Errorf("备份现有证书失败: %w", err)
	}
	restore := func(cause error) error {
		var restoreErrors []string
		if err := restoreSnapshot(certificateBackup); err != nil {
			restoreErrors = append(restoreErrors, "证书恢复失败")
		}
		if err := restoreSnapshot(privateKeyBackup); err != nil {
			restoreErrors = append(restoreErrors, "私钥恢复失败")
		}
		if err := r.restoreDeploymentConfiguration(configBackup); err != nil {
			restoreErrors = append(restoreErrors, "配置恢复失败")
		}
		if len(restoreErrors) > 0 {
			return fmt.Errorf("%v；%s，请人工恢复备份", cause, strings.Join(restoreErrors, "、"))
		}
		return cause
	}
	if err := atomicWriteDeploymentFile(targetCertificate, certificatePEM); err != nil {
		return restore(fmt.Errorf("安装证书失败: %w", err))
	}
	if err := atomicWriteDeploymentFile(targetPrivateKey, privateKeyPEM); err != nil {
		return restore(fmt.Errorf("安装私钥失败: %w", err))
	}
	changes := cloneStringMap(t.ConfigValues)
	changes["HTTPS_CERT_PATH"] = targetCertificate
	changes["HTTPS_KEY_PATH"] = targetPrivateKey
	raw, candidate, err := r.cfg.BuildDeploymentConfig(t.ConfigPath, changes)
	if err != nil {
		return restore(err)
	}
	if err := r.validateRenderedConfiguration(candidate, raw); err != nil {
		return restore(err)
	}
	if err := atomicWriteDeploymentFile(candidate.ConfigPath, raw); err != nil {
		return restore(fmt.Errorf("更新证书路径失败: %w", err))
	}
	if err := r.cfg.WriteDeploymentDescriptor(candidate.Mode, candidate.ConfigPath); err != nil {
		return restore(fmt.Errorf("更新配置路径失败: %w", err))
	}
	refreshed, err := r.cfg.RefreshDeployment()
	if err != nil {
		return restore(fmt.Errorf("重新加载配置失败: %w", err))
	}
	r.reportDeploymentState(refreshed)
	logCertificateInstalled(certificate)
	return nil
}

func validateCertificateInstallDomains(mode string, values map[string]string) error {
	candidate := &config.DeploymentState{Mode: mode, Values: values}
	if err := config.ValidateDeploymentDiagnostic("dns", candidate); err != nil {
		return fmt.Errorf("证书域名配置无效: %w", err)
	}
	return nil
}

func (r *Runner) persistCertificateBackup(certificate, privateKey certificateSnapshot) error {
	backupRoot := filepath.Join(r.cfg.BackupDir, "configuration", "certificates")
	if !certificate.exists && !privateKey.exists {
		return pruneCertificateBackups(backupRoot, r.cfg.KeepBackups)
	}
	if err := ensureSecureDirectory(backupRoot); err != nil {
		return err
	}
	backupDirectory := filepath.Join(backupRoot, time.Now().Format(certificateBackupTimestampLayout))
	if err := os.Mkdir(backupDirectory, 0o700); err != nil {
		return err
	}
	completed := false
	defer func() {
		if !completed {
			_ = os.RemoveAll(backupDirectory)
		}
	}()
	if certificate.exists {
		if err := atomicWriteDeploymentFile(filepath.Join(backupDirectory, "fullchain.pem"), certificate.content); err != nil {
			return err
		}
	}
	if privateKey.exists {
		if err := atomicWriteDeploymentFile(filepath.Join(backupDirectory, "privkey.pem"), privateKey.content); err != nil {
			return err
		}
	}
	if err := pruneCertificateBackups(backupRoot, r.cfg.KeepBackups); err != nil {
		return err
	}
	completed = true
	return nil
}

const certificateBackupTimestampLayout = "20060102-150405.000000000"

type managedCertificateBackup struct {
	name      string
	createdAt time.Time
}

func pruneCertificateBackups(backupRoot string, keep int) error {
	if keep <= 0 {
		keep = 3
	}
	if err := rejectTaskSymlinks(backupRoot); err != nil {
		return err
	}
	entries, err := os.ReadDir(backupRoot)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	managed := make([]managedCertificateBackup, 0, len(entries))
	for _, entry := range entries {
		if entry.Type()&os.ModeSymlink != 0 || !entry.IsDir() {
			continue
		}
		createdAt, parseErr := time.Parse(certificateBackupTimestampLayout, entry.Name())
		if parseErr != nil || createdAt.Format(certificateBackupTimestampLayout) != entry.Name() {
			continue
		}
		managed = append(managed, managedCertificateBackup{name: entry.Name(), createdAt: createdAt})
	}
	if len(managed) <= keep {
		return nil
	}
	sort.Slice(managed, func(left, right int) bool {
		return managed[left].createdAt.Before(managed[right].createdAt)
	})
	for _, backup := range managed[:len(managed)-keep] {
		candidate := filepath.Join(backupRoot, backup.name)
		relative, relErr := filepath.Rel(backupRoot, candidate)
		if relErr != nil || relative != backup.name || strings.Contains(relative, string(os.PathSeparator)) {
			return fmt.Errorf("证书备份清理路径越界")
		}
		info, statErr := os.Lstat(candidate)
		if statErr != nil {
			if os.IsNotExist(statErr) {
				continue
			}
			return statErr
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
			continue
		}
		if err := rejectTaskSymlinks(candidate); err != nil {
			return err
		}
		if err := os.RemoveAll(candidate); err != nil {
			return err
		}
	}
	return nil
}

func (r *Runner) cleanupCertificateStaging(requested string) {
	stagingRoot := filepath.Join(filepath.Dir(r.cfg.TaskFile), "cert-staging")
	path := filepath.Clean(strings.TrimSpace(requested))
	if !filepath.IsAbs(path) || filepath.Ext(path) != ".tmp" {
		return
	}
	relative, err := filepath.Rel(stagingRoot, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return
	}
	info, err := os.Lstat(path)
	if err == nil && info.Mode().IsRegular() && info.Mode()&os.ModeSymlink == 0 {
		_ = os.Remove(path)
	}
}

func (r *Runner) resolveCertificateStagingFile(requested string) (string, error) {
	stagingRoot := filepath.Join(filepath.Dir(r.cfg.TaskFile), "cert-staging")
	if err := rejectTaskSymlinks(stagingRoot); err != nil {
		return "", err
	}
	path := filepath.Clean(strings.TrimSpace(requested))
	if !filepath.IsAbs(path) {
		return "", fmt.Errorf("暂存路径必须为绝对路径")
	}
	relative, err := filepath.Rel(stagingRoot, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return "", fmt.Errorf("暂存路径越界")
	}
	if filepath.Ext(path) != ".tmp" {
		return "", fmt.Errorf("暂存文件类型错误")
	}
	if err := rejectTaskSymlinks(path); err != nil {
		return "", err
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("暂存文件不存在")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return "", fmt.Errorf("暂存文件权限过宽")
	}
	if info.Size() <= 0 || info.Size() > maxCertificateUploadBytes {
		return "", fmt.Errorf("暂存文件大小无效")
	}
	return path, nil
}

func readLimitedRegularFile(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("文件不可用")
	}
	if info.Size() <= 0 || info.Size() > maxCertificateUploadBytes {
		return nil, fmt.Errorf("文件大小必须在1字节至1MiB之间")
	}
	return os.ReadFile(path)
}

func validateCertificatePair(certificatePEM, privateKeyPEM []byte, domains ...string) (*x509.Certificate, error) {
	certificates, err := parseCertificates(certificatePEM)
	if err != nil {
		return nil, err
	}
	privateKey, err := parsePrivateKey(privateKeyPEM)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	for index, certificate := range certificates {
		if now.Before(certificate.NotBefore) {
			return nil, fmt.Errorf("证书链第%d张证书尚未生效", index+1)
		}
		if !now.Before(certificate.NotAfter) {
			return nil, fmt.Errorf("证书链第%d张证书已过期", index+1)
		}
		if index+1 < len(certificates) {
			issuer := certificates[index+1]
			if !bytes.Equal(certificate.RawIssuer, issuer.RawSubject) || certificate.CheckSignatureFrom(issuer) != nil {
				return nil, fmt.Errorf("证书链顺序或签名关系错误")
			}
		}
	}
	leap := certificates[0]
	if len(leap.ExtKeyUsage) > 0 {
		serverUsage := false
		for _, usage := range leap.ExtKeyUsage {
			if usage == x509.ExtKeyUsageServerAuth || usage == x509.ExtKeyUsageAny {
				serverUsage = true
				break
			}
		}
		if !serverUsage {
			return nil, fmt.Errorf("叶子证书不支持服务器认证")
		}
	}
	publicKey, err := x509.MarshalPKIXPublicKey(leap.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("读取证书公钥失败")
	}
	privatePublicKey, err := x509.MarshalPKIXPublicKey(privateKey.Public())
	if err != nil || !bytes.Equal(publicKey, privatePublicKey) {
		return nil, fmt.Errorf("证书与私钥不匹配")
	}
	for _, domain := range domains {
		domain = strings.TrimSpace(domain)
		if domain != "" {
			if err := leap.VerifyHostname(domain); err != nil {
				return nil, fmt.Errorf("证书未覆盖域名%s", domain)
			}
		}
	}
	return leap, nil
}

func parseCertificates(raw []byte) ([]*x509.Certificate, error) {
	remaining := raw
	certificates := make([]*x509.Certificate, 0, 2)
	for len(remaining) > 0 {
		block, rest := pem.Decode(remaining)
		if block == nil {
			break
		}
		remaining = rest
		if block.Type != "CERTIFICATE" {
			return nil, fmt.Errorf("完整证书链包含非证书内容")
		}
		certificate, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, fmt.Errorf("证书PEM格式错误")
		}
		certificates = append(certificates, certificate)
	}
	if len(certificates) == 0 {
		return nil, fmt.Errorf("未找到有效证书")
	}
	if len(bytes.TrimSpace(remaining)) > 0 {
		return nil, fmt.Errorf("完整证书链包含无效内容")
	}
	return certificates, nil
}

func parsePrivateKey(raw []byte) (crypto.Signer, error) {
	block, remaining := pem.Decode(raw)
	if block == nil || !strings.Contains(block.Type, "PRIVATE KEY") {
		return nil, fmt.Errorf("私钥PEM格式错误")
	}
	if len(bytes.TrimSpace(remaining)) > 0 {
		return nil, fmt.Errorf("私钥PEM包含多余内容")
	}
	if key, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		if signer, ok := key.(crypto.Signer); ok {
			return signer, nil
		}
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	if key, err := x509.ParseECPrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	return nil, fmt.Errorf("不支持的私钥格式")
}

func ensureSecureDirectory(path string) error {
	if err := rejectTaskSymlinks(path); err != nil {
		return err
	}
	if err := os.MkdirAll(path, 0o700); err != nil {
		return err
	}
	if err := rejectTaskSymlinks(path); err != nil {
		return err
	}
	return os.Chmod(path, 0o700)
}

func rejectTaskSymlinks(path string) error {
	current := filepath.Clean(path)
	for {
		info, err := os.Lstat(current)
		if err == nil && info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("路径禁止使用软链接")
		}
		if err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("检查路径失败")
		}
		parent := filepath.Dir(current)
		if parent == current {
			return nil
		}
		current = parent
	}
}

func snapshotFile(path string) (certificateSnapshot, error) {
	snapshot := certificateSnapshot{path: path}
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return snapshot, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return snapshot, fmt.Errorf("现有证书文件类型异常")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return snapshot, err
	}
	snapshot.content = content
	snapshot.exists = true
	return snapshot, nil
}

func restoreSnapshot(snapshot certificateSnapshot) error {
	if !snapshot.exists {
		if err := os.Remove(snapshot.path); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	return atomicWriteDeploymentFile(snapshot.path, snapshot.content)
}

func cloneStringMap(source map[string]string) map[string]string {
	result := make(map[string]string, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func logCertificateInstalled(certificate *x509.Certificate) {
	// 只记录序列号和有效期，不记录证书正文、私钥或上传暂存路径。
	fmt.Printf("HTTPS证书安装完成，序列号=%s，有效期至=%s\n",
		certificate.SerialNumber.Text(16), certificate.NotAfter.Format("2006-01-02 15:04:05"))
}
