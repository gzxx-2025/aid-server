// Package manifest 拉取并解析发布方版本清单（latest.json）中升级器所需字段。
package manifest

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"time"
)

// trustedPublicKey 由发布构建通过 -ldflags 注入，值为 Ed25519 公钥的 Base64。
var trustedPublicKey string

// maxManifestBytes 清单大小上限，防异常源。
const maxManifestBytes = 256 * 1024

// UpdaterPackage 升级器制品（按架构区分）。
type UpdaterPackage struct {
	URL     string   `json:"url"`
	Mirrors []string `json:"mirrors,omitempty"`
	SHA256  string   `json:"sha256"`
}

// ChannelRelease 单渠道发行信息（正式版在顶层，测试版在 beta 字段）。
type ChannelRelease struct {
	ProductVersion   string            `json:"productVersion"`
	SourceBuild      bool              `json:"sourceBuild"`
	PackageURL       string            `json:"packageUrl"`
	PackageMirrors   []string          `json:"packageMirrors,omitempty"`
	PackageSHA256    string            `json:"packageSha256"`
	Updater          ChannelUpdater    `json:"updater"`
	RollbackReleases []RollbackRelease `json:"rollbackReleases"`
}

// ChannelUpdater 渠道内升级器制品信息。
type ChannelUpdater struct {
	Version  string                    `json:"version"`
	Packages map[string]UpdaterPackage `json:"packages"`
}

// Manifest 仅包含升级器关心的清单字段。
type Manifest struct {
	ProductVersion   string            `json:"productVersion"`
	SourceBuild      bool              `json:"sourceBuild"`
	PackageURL       string            `json:"packageUrl"`
	PackageMirrors   []string          `json:"packageMirrors,omitempty"`
	PackageSHA256    string            `json:"packageSha256"`
	Updater          ChannelUpdater    `json:"updater"`
	RollbackReleases []RollbackRelease `json:"rollbackReleases"`
	Beta             *ChannelRelease   `json:"beta"`
	Signature        Signature         `json:"signature"`
}

type RollbackRelease struct {
	Version    string   `json:"version"`
	PackageURL string   `json:"packageUrl"`
	Mirrors    []string `json:"mirrors,omitempty"`
	SHA256     string   `json:"sha256"`
}

type Signature struct {
	Algorithm string `json:"algorithm"`
	Value     string `json:"value"`
}

// Fetch 拉取并解析清单。
func Fetch(url string, timeout time.Duration) (*Manifest, error) {
	if !isSecureURL(url) {
		return nil, fmt.Errorf("非法清单地址: %s", url)
	}
	client := &http.Client{Timeout: timeout, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 10 {
			return fmt.Errorf("重定向次数过多")
		}
		if !isSecureURL(req.URL.String()) {
			return fmt.Errorf("清单地址发生非安全重定向")
		}
		return nil
	}}
	resp, err := client.Get(url)
	if err != nil {
		return nil, fmt.Errorf("拉取清单失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("清单响应异常: HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxManifestBytes+1))
	if err != nil {
		return nil, fmt.Errorf("读取清单失败: %w", err)
	}
	if len(raw) > maxManifestBytes {
		return nil, fmt.Errorf("清单超过大小上限")
	}
	m := &Manifest{}
	if err := json.Unmarshal(raw, m); err != nil {
		return nil, fmt.Errorf("解析清单失败: %w", err)
	}
	if err := Verify(raw); err != nil {
		return nil, err
	}
	return m, nil
}

// Verify 验证发布清单的 Ed25519 签名。签名覆盖移除 signature 字段后的规范 JSON。
func Verify(raw []byte) error {
	if strings.TrimSpace(trustedPublicKey) == "" {
		return fmt.Errorf("升级器未内置清单验签公钥")
	}
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		return fmt.Errorf("解析待验签清单失败: %w", err)
	}
	signatureValue, ok := document["signature"].(map[string]any)
	if !ok || signatureValue["algorithm"] != "Ed25519" {
		return fmt.Errorf("清单签名信息缺失")
	}
	signatureText, _ := signatureValue["value"].(string)
	delete(document, "signature")
	canonical, err := json.Marshal(document)
	if err != nil {
		return fmt.Errorf("规范化清单失败: %w", err)
	}
	publicKey, err := base64.StdEncoding.DecodeString(trustedPublicKey)
	if err != nil || len(publicKey) != ed25519.PublicKeySize {
		return fmt.Errorf("清单验签公钥非法")
	}
	signature, err := base64.StdEncoding.DecodeString(signatureText)
	if err != nil || !ed25519.Verify(ed25519.PublicKey(publicKey), canonical, signature) {
		return fmt.Errorf("清单签名校验失败")
	}
	return nil
}

func isSecureURL(raw string) bool {
	parsed, err := url.Parse(raw)
	return err == nil && parsed.Scheme == "https" && parsed.Host != "" && parsed.User == nil
}

// MatchProductRelease 判断任务目标是否与顶层正式版或嵌套测试版一致。
func (m *Manifest) MatchProductRelease(version, packageURL, sha256 string) bool {
	_, ok := m.ProductPackageMirrors(version, packageURL, sha256)
	return ok
}

// MatchProductVersion 判断目标版本是否存在于已签名的正式版或测试版清单中。
// 源码构建不消费发布包 URL/SHA，只允许构建签名清单明确发布的版本。
func (m *Manifest) MatchProductVersion(version string) bool {
	target := strings.TrimSpace(version)
	if target == "" {
		return false
	}
	if strings.TrimSpace(m.ProductVersion) == target {
		return true
	}
	return m.Beta != nil && strings.TrimSpace(m.Beta.ProductVersion) == target
}

// MatchSourceBuildVersion 判断签名清单中的目标版本是否明确启用源码构建。
// 该判断同时用于兼容旧版后台：旧后台仍提交 packageUrl/sha256，但新版升级器
// 看到目标渠道 sourceBuild=true 后会忽略兼容字段，改走固定版本标签构建。
func (m *Manifest) MatchSourceBuildVersion(version string) bool {
	target := strings.TrimSpace(version)
	if target == "" {
		return false
	}
	if strings.TrimSpace(m.ProductVersion) == target {
		return m.SourceBuild
	}
	return m.Beta != nil && strings.TrimSpace(m.Beta.ProductVersion) == target && m.Beta.SourceBuild
}

// ProductPackageMirrors 返回已签名清单中与任务匹配的产品包镜像地址。
// 旧清单没有 packageMirrors 时返回空切片，仍只使用 packageUrl。
func (m *Manifest) ProductPackageMirrors(version, packageURL, sha256 string) ([]string, bool) {
	if matchPackage(m.ProductVersion, m.PackageURL, m.PackageSHA256, version, packageURL, sha256) {
		return append([]string(nil), m.PackageMirrors...), true
	}
	if m.Beta == nil {
		return nil, false
	}
	if matchPackage(m.Beta.ProductVersion, m.Beta.PackageURL, m.Beta.PackageSHA256, version, packageURL, sha256) {
		return append([]string(nil), m.Beta.PackageMirrors...), true
	}
	return nil, false
}

// MatchRollbackRelease 判断回退目标是否出现在正式或测试渠道的可回退列表中。
func (m *Manifest) MatchRollbackRelease(version, packageURL, sha256 string) bool {
	_, ok := m.RollbackPackageMirrors(version, packageURL, sha256)
	return ok
}

// RollbackPackageMirrors 返回已签名回退记录中与任务匹配的镜像地址。
func (m *Manifest) RollbackPackageMirrors(version, packageURL, sha256 string) ([]string, bool) {
	if mirrors, ok := matchRollbackList(m.RollbackReleases, version, packageURL, sha256); ok {
		return mirrors, true
	}
	if m.Beta == nil {
		return nil, false
	}
	return matchRollbackList(m.Beta.RollbackReleases, version, packageURL, sha256)
}

// SelectUpdaterForVersion 按任务目标版本选择正式或测试渠道的升级器制品集合。
func (m *Manifest) SelectUpdaterForVersion(targetVersion string) (*ChannelUpdater, error) {
	target := strings.TrimSpace(targetVersion)
	if strings.TrimSpace(m.Updater.Version) == target {
		return &m.Updater, nil
	}
	if m.Beta != nil && strings.TrimSpace(m.Beta.Updater.Version) == target {
		return &m.Beta.Updater, nil
	}
	return nil, fmt.Errorf("清单升级器版本与任务目标不一致")
}

// SelectUpdaterPackage 按当前操作系统与架构选择升级器制品。
func (u *ChannelUpdater) SelectUpdaterPackage() (*UpdaterPackage, error) {
	key := fmt.Sprintf("%s_%s", runtime.GOOS, runtime.GOARCH)
	pkg, ok := u.Packages[key]
	if !ok {
		return nil, fmt.Errorf("清单未提供当前平台(%s)的升级器制品", key)
	}
	if strings.TrimSpace(pkg.URL) == "" || len(strings.TrimSpace(pkg.SHA256)) != 64 {
		return nil, fmt.Errorf("升级器制品信息不完整")
	}
	return &pkg, nil
}

// SelectUpdaterPackage 兼容旧调用：仅看顶层正式版升级器。
func (m *Manifest) SelectUpdaterPackage() (*UpdaterPackage, error) {
	return m.Updater.SelectUpdaterPackage()
}

func matchPackage(productVersion, packageURL, packageSHA, version, taskURL, taskSHA string) bool {
	return productVersion == version && packageURL == taskURL && strings.EqualFold(packageSHA, taskSHA)
}

func matchRollbackList(list []RollbackRelease, version, packageURL, sha256 string) ([]string, bool) {
	for _, release := range list {
		if release.Version == version && release.PackageURL == packageURL &&
			strings.EqualFold(release.SHA256, sha256) {
			return append([]string(nil), release.Mirrors...), true
		}
	}
	return nil, false
}
