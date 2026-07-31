// Package sysctl 封装后端服务控制（systemd / docker）与服务健康检查。
package sysctl

import (
	"fmt"
	"net/http"
	"os/exec"
	"strings"
	"time"
)

// 支持的服务管理方式。
const (
	ManagerSystemd = "systemd"
	ManagerDocker  = "docker"
)

// StopService 停止后端服务；service 为 systemd 单元名或 docker 容器名。
func StopService(manager, service string) error {
	if manager == ManagerDocker {
		return runCommand("docker", "stop", service)
	}
	return runCommand("systemctl", "stop", service)
}

// StartService 启动后端服务；service 为 systemd 单元名或 docker 容器名。
func StartService(manager, service string) error {
	if manager == ManagerDocker {
		return runCommand("docker", "start", service)
	}
	return runCommand("systemctl", "start", service)
}

func runCommand(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s 失败: %v, 输出: %s",
			name, strings.Join(args, " "), err, strings.TrimSpace(string(output)))
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
