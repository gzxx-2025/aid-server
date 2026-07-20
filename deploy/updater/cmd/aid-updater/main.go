// aid-updater 是 AID 平台的独立升级器：
// 消费后端投递的升级任务，执行系统升级、版本回退与升级器自升级。
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"aid-updater/internal/config"
	"aid-updater/internal/health"
	"aid-updater/internal/task"
)

// version 由发布构建注入：go build -ldflags "-X main.version=x.y.z"
var version = "dev"

func main() {
	configPath := flag.String("config", "/etc/aid-updater/config.json", "配置文件路径")
	showVersion := flag.Bool("version", false, "打印版本后退出")
	flag.Parse()

	if *showVersion {
		fmt.Println(version)
		return
	}

	log.SetFlags(log.LstdFlags)
	log.Printf("aid-updater %s 启动, 配置: %s", version, *configPath)

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("加载配置失败: %v", err)
	}
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

	reporter := health.NewReporter(cfg.HealthFile, version)
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
