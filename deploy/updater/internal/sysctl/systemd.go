// Package sysctl 封装 systemd 服务控制与服务健康检查。
package sysctl

import (
	"fmt"
	"net/http"
	"os/exec"
	"strings"
	"time"
)

// StopService 停止 systemd 服务。
func StopService(service string) error {
	return runSystemctl("stop", service)
}

// StartService 启动 systemd 服务。
func StartService(service string) error {
	return runSystemctl("start", service)
}

func runSystemctl(action, service string) error {
	cmd := exec.Command("systemctl", action, service)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("systemctl %s %s 失败: %v, 输出: %s",
			action, service, err, strings.TrimSpace(string(output)))
	}
	return nil
}

// WaitHealthy 轮询健康检查地址直到返回 2xx/3xx 或超时。
func WaitHealthy(url string, timeout time.Duration) error {
	client := &http.Client{Timeout: 5 * time.Second}
	deadline := time.Now().Add(timeout)
	var lastErr error
	for time.Now().Before(deadline) {
		resp, err := client.Get(url)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 400 {
				return nil
			}
			lastErr = fmt.Errorf("健康检查响应 HTTP %d", resp.StatusCode)
		} else {
			lastErr = err
		}
		time.Sleep(2 * time.Second)
	}
	return fmt.Errorf("健康检查超时: %v", lastErr)
}
