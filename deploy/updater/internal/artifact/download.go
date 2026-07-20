// Package artifact 负责制品下载、校验与解压。
package artifact

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// maxDownloadBytes 单个制品大小上限（2 GiB），防异常源拖垮磁盘。
const maxDownloadBytes = 2 << 30

// DownloadFile 下载 url 到 dst（覆盖写），返回实际写入字节数。
func DownloadFile(url, dst string, timeout time.Duration) (int64, error) {
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		return 0, fmt.Errorf("非法下载地址: %s", url)
	}
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(url)
	if err != nil {
		return 0, fmt.Errorf("下载请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return 0, fmt.Errorf("下载响应异常: HTTP %d", resp.StatusCode)
	}

	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return 0, fmt.Errorf("创建下载目录失败: %w", err)
	}
	out, err := os.Create(dst)
	if err != nil {
		return 0, fmt.Errorf("创建下载文件失败: %w", err)
	}
	defer out.Close()

	written, err := io.Copy(out, io.LimitReader(resp.Body, maxDownloadBytes+1))
	if err != nil {
		return written, fmt.Errorf("写入下载文件失败: %w", err)
	}
	if written > maxDownloadBytes {
		return written, fmt.Errorf("制品超过大小上限")
	}
	return written, nil
}

// VerifySHA256 校验文件摘要（大小写不敏感）。
func VerifySHA256(path, expected string) error {
	expected = strings.ToLower(strings.TrimSpace(expected))
	if len(expected) != 64 {
		return fmt.Errorf("SHA256 期望值非法")
	}
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("打开文件失败: %w", err)
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return fmt.Errorf("读取文件失败: %w", err)
	}
	actual := hex.EncodeToString(h.Sum(nil))
	if actual != expected {
		return fmt.Errorf("SHA256 校验不一致: 期望 %s 实际 %s", expected, actual)
	}
	return nil
}
