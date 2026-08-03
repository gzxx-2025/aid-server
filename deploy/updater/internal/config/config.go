// Package config 负责加载并校验升级器配置。
package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
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
	// ServiceManager 服务管理方式：systemd（默认）或 docker
	ServiceManager string `json:"serviceManager"`
	// BackendService 服务标识：systemd 单元名或 docker 容器名
	BackendService string `json:"backendService"`
	// RestartServices 升级完成后需要依次重启的附属服务（如用户端 SSR、docker 部署的 nginx），
	// 语义与 BackendService 一致（systemd 单元名或 docker 容器名），可为空
	RestartServices []string `json:"restartServices"`
	// HealthCheckURL 服务端健康检查地址
	HealthCheckURL string `json:"healthCheckUrl"`
	// HealthCheckTimeoutSeconds 健康检查超时（秒）
	HealthCheckTimeoutSeconds int `json:"healthCheckTimeoutSeconds"`
}

// Database 描述可选的数据库操作配置；未启用时跳过 SQL 执行与库备份。
type Database struct {
	Enabled bool   `json:"enabled"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
	Name    string `json:"name"`
	User    string `json:"user"`
	// Password 数据库密码；经容器执行时通过环境变量传入容器，不落命令行
	Password string `json:"password"`
	// ExecContainer 非空时 mysql/mysqldump 经 `docker exec <容器>` 执行（Docker 部署
	// 无需宿主机安装 MySQL 客户端）；为空时直接调用本机客户端
	ExecContainer string `json:"execContainer"`
}

// Deployment 描述部署配置文件的受控位置。业务运行配置始终以该文件为唯一真源，
// 升级器只允许在默认文件或 allowedConfigRoot 下读写，避免后台形成任意文件访问能力。
type Deployment struct {
	DescriptorFile    string `json:"descriptorFile"`
	ConfigPath        string `json:"configPath"`
	DefaultConfigPath string `json:"defaultConfigPath"`
	AllowedConfigRoot string `json:"allowedConfigRoot"`
	ComposeFile       string `json:"composeFile"`
	ManagerScript     string `json:"managerScript"`
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
	// KeepBackups 升级/回退前自动备份的保留份数，超出按时间从旧到新清理
	KeepBackups int `json:"keepBackups"`
	// SourceBuildScript 按版本标签拉取三端源码并组装本地升级包的脚本路径
	SourceBuildScript string `json:"sourceBuildScript"`
	// SourceBuildTimeoutSeconds 三端源码构建总超时（秒）
	SourceBuildTimeoutSeconds int        `json:"sourceBuildTimeoutSeconds"`
	Install                   Install    `json:"install"`
	Database                  Database   `json:"database"`
	Deployment                Deployment `json:"deployment"`
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
	// 部署配置文件是数据库连接等运行参数的唯一真源；升级器每次启动都重新加载，
	// 避免管理员修改配置后升级器仍使用旧凭据执行备份或增量 SQL。
	if _, err := cfg.RefreshDeployment(); err != nil {
		return nil, fmt.Errorf("加载部署配置失败: %w", err)
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
	if c.KeepBackups <= 0 {
		c.KeepBackups = 3
	}
	if strings.TrimSpace(c.SourceBuildScript) == "" && strings.TrimSpace(c.Install.BackendJar) != "" {
		dataRoot := filepath.Dir(filepath.Dir(c.Install.BackendJar))
		c.SourceBuildScript = filepath.Join(dataRoot, "installer", "deploy", "build-release-from-source.sh")
	}
	if c.SourceBuildTimeoutSeconds <= 0 {
		c.SourceBuildTimeoutSeconds = 7200
	}
	if strings.TrimSpace(c.Install.ServiceManager) == "" {
		c.Install.ServiceManager = "systemd"
	}
	if c.Install.HealthCheckTimeoutSeconds <= 0 {
		c.Install.HealthCheckTimeoutSeconds = 180
	}
	if c.Database.Port <= 0 {
		c.Database.Port = 3306
	}
	if strings.TrimSpace(c.Install.BackendJar) != "" {
		dataRoot := filepath.Dir(filepath.Dir(c.Install.BackendJar))
		if strings.TrimSpace(c.Deployment.DescriptorFile) == "" {
			c.Deployment.DescriptorFile = filepath.Join(dataRoot, "config", "deployment.json")
		}
		if strings.TrimSpace(c.Deployment.AllowedConfigRoot) == "" {
			c.Deployment.AllowedConfigRoot = filepath.Join(dataRoot, "config")
		}
		if strings.TrimSpace(c.Deployment.ManagerScript) == "" {
			c.Deployment.ManagerScript = filepath.Join(dataRoot, "installer", "deploy", "aid.sh")
		}
		if strings.TrimSpace(c.Deployment.ComposeFile) == "" {
			c.Deployment.ComposeFile = filepath.Join(dataRoot, "installer", "deploy", "docker", "docker-compose.yml")
		}
		if strings.TrimSpace(c.Deployment.DefaultConfigPath) == "" {
			if strings.EqualFold(c.Install.ServiceManager, "docker") {
				c.Deployment.DefaultConfigPath = filepath.Join(dataRoot, "installer", "deploy", "docker", ".env")
			} else {
				c.Deployment.DefaultConfigPath = filepath.Join(dataRoot, "aid-deploy.conf")
			}
		}
		if strings.TrimSpace(c.Deployment.ConfigPath) == "" {
			c.Deployment.ConfigPath = c.Deployment.DefaultConfigPath
		}
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
	manager := strings.ToLower(strings.TrimSpace(c.Install.ServiceManager))
	if manager != "systemd" && manager != "docker" {
		return fmt.Errorf("install.serviceManager 仅支持 systemd 或 docker")
	}
	c.Install.ServiceManager = manager
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
	if strings.TrimSpace(c.Deployment.ConfigPath) == "" || strings.TrimSpace(c.Deployment.DefaultConfigPath) == "" {
		return fmt.Errorf("配置缺失: deployment.configPath/defaultConfigPath")
	}
	return nil
}
