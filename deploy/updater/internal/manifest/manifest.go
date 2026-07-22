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
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}

// Manifest 仅包含升级器关心的清单字段。
type Manifest struct {
	ProductVersion string `json:"productVersion"`
	PackageURL     string `json:"packageUrl"`
	PackageSHA256  string `json:"packageSha256"`
	Updater        struct {
		Version  string                    `json:"version"`
		Packages map[string]UpdaterPackage `json:"packages"`
	} `json:"updater"`
	RollbackReleases []RollbackRelease `json:"rollbackReleases"`
	Signature        Signature         `json:"signature"`
}

type RollbackRelease struct {
	Version    string `json:"version"`
	PackageURL string `json:"packageUrl"`
	SHA256     string `json:"sha256"`
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

// SelectUpdaterPackage 按当前操作系统与架构选择升级器制品。
func (m *Manifest) SelectUpdaterPackage() (*UpdaterPackage, error) {
	key := fmt.Sprintf("%s_%s", runtime.GOOS, runtime.GOARCH)
	pkg, ok := m.Updater.Packages[key]
	if !ok {
		return nil, fmt.Errorf("清单未提供当前平台(%s)的升级器制品", key)
	}
	if strings.TrimSpace(pkg.URL) == "" || len(strings.TrimSpace(pkg.SHA256)) != 64 {
		return nil, fmt.Errorf("升级器制品信息不完整")
	}
	return &pkg, nil
}
