// Package artifact 负责制品下载、校验与解压。
package artifact

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// maxDownloadBytes 单个制品大小上限（2 GiB），防异常源拖垮磁盘。
const maxDownloadBytes = 2 << 30

// DownloadFile 下载 url 到 dst（覆盖写），返回实际写入字节数。
func DownloadFile(url, dst string, timeout time.Duration) (int64, error) {
	return DownloadFileWithLimitContext(context.Background(), url, dst, timeout, maxDownloadBytes)
}

// DownloadFileWithLimit downloads a small signed artifact with a caller-owned
// hard size limit. It is used for executable release metadata such as the
// target source builder, which must never inherit the 2 GiB package allowance.
func DownloadFileWithLimit(url, dst string, timeout time.Duration, maxBytes int64) (int64, error) {
	return DownloadFileWithLimitContext(context.Background(), url, dst, timeout, maxBytes)
}

// DownloadFileWithLimitContext downloads an artifact and stops promptly when
// the caller cancels the active upgrade task.
func DownloadFileWithLimitContext(ctx context.Context, url, dst string, timeout time.Duration, maxBytes int64) (int64, error) {
	if maxBytes <= 0 || maxBytes > maxDownloadBytes {
		return 0, fmt.Errorf("非法下载大小上限")
	}
	if !isSecureURL(url) {
		return 0, fmt.Errorf("非法下载地址: %s", url)
	}
	client := &http.Client{Timeout: timeout, CheckRedirect: secureRedirect}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, fmt.Errorf("创建下载请求失败: %w", err)
	}
	resp, err := client.Do(request)
	if err != nil {
		return 0, fmt.Errorf("下载请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return 0, fmt.Errorf("下载响应异常: HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > maxBytes {
		return 0, fmt.Errorf("制品超过大小上限")
	}

	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return 0, fmt.Errorf("创建下载目录失败: %w", err)
	}
	out, err := os.Create(dst)
	if err != nil {
		return 0, fmt.Errorf("创建下载文件失败: %w", err)
	}
	defer out.Close()

	written, err := io.Copy(out, io.LimitReader(resp.Body, maxBytes+1))
	if err != nil {
		return written, fmt.Errorf("写入下载文件失败: %w", err)
	}
	if written > maxBytes {
		return written, fmt.Errorf("制品超过大小上限")
	}
	return written, nil
}

// DownloadAndVerify 依次尝试主地址和镜像地址，每次下载后都校验同一个 SHA256。
// 只有下载与校验同时成功才会返回，失败来源留下的文件会在切换前清理。
func DownloadAndVerify(urls []string, dst, expectedSHA256 string, timeout time.Duration) (string, int64, error) {
	return downloadAndVerify(urls, dst, expectedSHA256, timeout, DownloadFile)
}

// DownloadAndVerifyContext downloads a release artifact with cancellation.
func DownloadAndVerifyContext(ctx context.Context, urls []string, dst, expectedSHA256 string, timeout time.Duration) (string, int64, error) {
	return downloadAndVerifyContext(ctx, urls, dst, expectedSHA256, timeout, func(requestContext context.Context, source, target string, requestTimeout time.Duration) (int64, error) {
		return DownloadFileWithLimitContext(requestContext, source, target, requestTimeout, maxDownloadBytes)
	})
}

// DownloadAndVerifyWithLimit applies mirror fallback, SHA256 verification and
// a strict byte limit to small signed artifacts.
func DownloadAndVerifyWithLimit(urls []string, dst, expectedSHA256 string, timeout time.Duration, maxBytes int64) (string, int64, error) {
	return downloadAndVerify(urls, dst, expectedSHA256, timeout, func(source, target string, requestTimeout time.Duration) (int64, error) {
		return DownloadFileWithLimit(source, target, requestTimeout, maxBytes)
	})
}

// DownloadAndVerifyWithLimitContext downloads a small signed artifact with cancellation.
func DownloadAndVerifyWithLimitContext(ctx context.Context, urls []string, dst, expectedSHA256 string, timeout time.Duration, maxBytes int64) (string, int64, error) {
	return downloadAndVerifyContext(ctx, urls, dst, expectedSHA256, timeout, func(requestContext context.Context, source, target string, requestTimeout time.Duration) (int64, error) {
		return DownloadFileWithLimitContext(requestContext, source, target, requestTimeout, maxBytes)
	})
}

type downloadFunc func(string, string, time.Duration) (int64, error)

type contextDownloadFunc func(context.Context, string, string, time.Duration) (int64, error)

func downloadAndVerify(urls []string, dst, expectedSHA256 string, timeout time.Duration, download downloadFunc) (string, int64, error) {
	return downloadAndVerifyContext(context.Background(), urls, dst, expectedSHA256, timeout, func(_ context.Context, source, target string, requestTimeout time.Duration) (int64, error) {
		return download(source, target, requestTimeout)
	})
}

func downloadAndVerifyContext(ctx context.Context, urls []string, dst, expectedSHA256 string, timeout time.Duration, download contextDownloadFunc) (string, int64, error) {
	sources := uniqueSources(urls)
	if len(sources) == 0 {
		return "", 0, fmt.Errorf("下载地址为空")
	}

	var failures []string
	for index, source := range sources {
		if err := ctx.Err(); err != nil {
			_ = os.Remove(dst)
			return "", 0, err
		}
		label := "主下载源"
		if index > 0 {
			label = fmt.Sprintf("镜像源%d", index)
		}
		log.Printf("尝试%s: %s", label, source)

		written, err := download(ctx, source, dst, timeout)
		if err == nil {
			err = VerifySHA256(dst, expectedSHA256)
		}
		if err == nil {
			log.Printf("%s下载并校验成功", label)
			return source, written, nil
		}

		_ = os.Remove(dst)
		failures = append(failures, fmt.Sprintf("%s: %v", label, err))
		if index+1 < len(sources) {
			log.Printf("%s失败，切换下一下载源: %v", label, err)
		}
	}
	return "", 0, fmt.Errorf("所有下载源均失败: %s", strings.Join(failures, "; "))
}

func uniqueSources(urls []string) []string {
	seen := make(map[string]struct{}, len(urls))
	result := make([]string, 0, len(urls))
	for _, raw := range urls {
		source := strings.TrimSpace(raw)
		if source == "" {
			continue
		}
		if _, exists := seen[source]; exists {
			continue
		}
		seen[source] = struct{}{}
		result = append(result, source)
	}
	return result
}

func isSecureURL(raw string) bool {
	parsed, err := url.Parse(raw)
	return err == nil && parsed.Scheme == "https" && parsed.Host != "" && parsed.User == nil
}

func secureRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return fmt.Errorf("重定向次数过多")
	}
	if !isSecureURL(req.URL.String()) {
		return fmt.Errorf("下载地址发生非安全重定向")
	}
	return nil
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
