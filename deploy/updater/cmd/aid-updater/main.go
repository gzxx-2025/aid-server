// aid-updater 是 AID 平台的独立升级器：
// 消费后端投递的升级任务，执行系统升级、版本回退与升级器自升级。
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"aid-updater/internal/config"
	"aid-updater/internal/health"
	"aid-updater/internal/task"
)

// version 由发布构建注入：go build -ldflags "-X main.version=x.y.z"
var version = "dev"

// maxLogFileBytes 日志文件上限：超限时轮转为 .old，防止长期运行写满磁盘。
const maxLogFileBytes = 5 * 1024 * 1024

// setupLogFile 让日志同时写入健康文件同目录的 updater.log：
// 后端挂载同一目录即可读取日志在页面展示，systemd/docker 两种部署方式通用。
func setupLogFile(healthFile string) {
	logPath := filepath.Join(filepath.Dir(healthFile), "updater.log")
	if info, err := os.Stat(logPath); err == nil && info.Size() > maxLogFileBytes {
		// 简单轮转：保留一份 .old，失败不阻断启动
		_ = os.Remove(logPath + ".old")
		_ = os.Rename(logPath, logPath+".old")
	}
	file, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		log.Printf("打开日志文件失败（仅输出到控制台）: %v", err)
		return
	}
	log.SetOutput(io.MultiWriter(os.Stderr, file))
}

func main() {
	configPath := flag.String("config", "/etc/aid-updater/config.json", "配置文件路径")
	showVersion := flag.Bool("version", false, "打印版本后退出")
	flag.Parse()

	if *showVersion {
		fmt.Println(version)
		return
	}

	log.SetFlags(log.LstdFlags)

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("加载配置失败: %v", err)
	}
	if mkErr := os.MkdirAll(filepath.Dir(cfg.HealthFile), 0o755); mkErr != nil {
		log.Fatalf("创建健康文件目录失败: %v", mkErr)
	}
	setupLogFile(cfg.HealthFile)
	log.Printf("aid-updater %s 启动, 配置: %s", version, *configPath)
	if err := os.MkdirAll(cfg.WorkDir, 0o755); err != nil {
		log.Fatalf("创建工作目录失败: %v", err)
	}
	if err := os.MkdirAll(cfg.BackupDir, 0o755); err != nil {
		log.Fatalf("创建备份目录失败: %v", err)
	}

	// 清理上次自升级遗留的旧二进制
	task.CleanupSelfUpgradeLeftover()

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	reporter := health.NewReporter(cfg.HealthFile, version, cfg.Install.ServiceManager)
	reporter.Start(ctx, time.Duration(cfg.HeartbeatIntervalSeconds)*time.Second)

	runner := task.NewRunner(cfg, reporter, version)
	runner.RecoverInterrupted()

	ticker := time.NewTicker(time.Duration(cfg.PollIntervalSeconds) * time.Second)
	defer ticker.Stop()

	log.Printf("开始轮询任务文件: %s", cfg.TaskFile)
	for {
		select {
		case <-ctx.Done():
			log.Printf("收到退出信号，aid-updater 停止")
			reporter.Flush(health.StatusStopped)
			return
		case <-ticker.C:
			runner.PollOnce()
			if runner.ExitRequested() {
				// 自升级完成：退出进程，由 systemd Restart=always 拉起新版本
				log.Printf("自升级完成，进程退出等待 systemd 重启")
				return
			}
		}
	}
}
