// Package manifest 拉取并解析发布方版本清单（latest.json）中升级器所需字段。
package manifest

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"runtime"
	"strings"
	"time"
)

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
	Updater        struct {
		Version  string                    `json:"version"`
		Packages map[string]UpdaterPackage `json:"packages"`
	} `json:"updater"`
}

// Fetch 拉取并解析清单。
func Fetch(url string, timeout time.Duration) (*Manifest, error) {
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		return nil, fmt.Errorf("非法清单地址: %s", url)
	}
	client := &http.Client{Timeout: timeout}
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
	return m, nil
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
