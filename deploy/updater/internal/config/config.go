// Package config 负责加载并校验升级器配置。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
)

// Install 描述 AID 三端产物的部署位置与服务管理方式。
type Install struct {
	// BackendJar 服务端 jar 的部署路径
	BackendJar string `json:"backendJar"`
	// AdminDist 管理端静态资源目录（可为空表示不由升级器管理）
	AdminDist string `json:"adminDist"`
	// WebDist 用户端静态资源目录（可为空表示不由升级器管理）
	WebDist string `json:"webDist"`
	// BackendService 服务端 systemd 服务名
	BackendService string `json:"backendService"`
	// HealthCheckURL 服务端健康检查地址
	HealthCheckURL string `json:"healthCheckUrl"`
	// HealthCheckTimeoutSeconds 健康检查超时（秒）
	HealthCheckTimeoutSeconds int `json:"healthCheckTimeoutSeconds"`
}

// Database 描述可选的数据库操作配置；未启用时跳过 SQL 执行与库备份。
type Database struct {
	Enabled  bool   `json:"enabled"`
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Name     string `json:"name"`
	User     string `json:"user"`
	Password string `json:"password"`
}

// Config 为升级器全量配置。
type Config struct {
	// HealthFile 健康文件路径（与后台「升级源配置」保持一致）
	HealthFile string `json:"healthFile"`
	// TaskFile 任务文件路径（后端原子写入，升级器消费）
	TaskFile string `json:"taskFile"`
	// WorkDir 任务工作目录（认领的任务、下载与解压产物）
	WorkDir string `json:"workDir"`
	// BackupDir 备份根目录
	BackupDir string `json:"backupDir"`
	// PollIntervalSeconds 任务轮询间隔（秒）
	PollIntervalSeconds int `json:"pollIntervalSeconds"`
	// HeartbeatIntervalSeconds 心跳写入间隔（秒）
	HeartbeatIntervalSeconds int `json:"heartbeatIntervalSeconds"`
	// DownloadTimeoutSeconds 单个制品下载超时（秒）
	DownloadTimeoutSeconds int `json:"downloadTimeoutSeconds"`
	Install                Install  `json:"install"`
	Database               Database `json:"database"`
}

// Load 读取 JSON 配置并应用默认值与基础校验。
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取配置文件失败: %w", err)
	}
	cfg := &Config{}
	if err := json.Unmarshal(raw, cfg); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %w", err)
	}
	cfg.applyDefaults()
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) applyDefaults() {
	if c.PollIntervalSeconds <= 0 {
		c.PollIntervalSeconds = 3
	}
	if c.HeartbeatIntervalSeconds <= 0 {
		c.HeartbeatIntervalSeconds = 5
	}
	if c.DownloadTimeoutSeconds <= 0 {
		c.DownloadTimeoutSeconds = 600
	}
	if c.Install.HealthCheckTimeoutSeconds <= 0 {
		c.Install.HealthCheckTimeoutSeconds = 180
	}
	if c.Database.Port <= 0 {
		c.Database.Port = 3306
	}
}

func (c *Config) validate() error {
	if strings.TrimSpace(c.HealthFile) == "" {
		return fmt.Errorf("配置缺失: healthFile")
	}
	if strings.TrimSpace(c.TaskFile) == "" {
		return fmt.Errorf("配置缺失: taskFile")
	}
	if strings.TrimSpace(c.WorkDir) == "" {
		return fmt.Errorf("配置缺失: workDir")
	}
	if strings.TrimSpace(c.BackupDir) == "" {
		return fmt.Errorf("配置缺失: backupDir")
	}
	if strings.TrimSpace(c.Install.BackendJar) == "" {
		return fmt.Errorf("配置缺失: install.backendJar")
	}
	if strings.TrimSpace(c.Install.BackendService) == "" {
		return fmt.Errorf("配置缺失: install.backendService")
	}
	if strings.TrimSpace(c.Install.HealthCheckURL) == "" {
		return fmt.Errorf("配置缺失: install.healthCheckUrl")
	}
	if c.Database.Enabled {
		if strings.TrimSpace(c.Database.Host) == "" || strings.TrimSpace(c.Database.Name) == "" ||
			strings.TrimSpace(c.Database.User) == "" {
			return fmt.Errorf("配置缺失: database.host/name/user")
		}
	}
	return nil
}
