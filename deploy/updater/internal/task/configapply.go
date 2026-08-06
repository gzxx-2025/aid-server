package task

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
	"aid-updater/internal/health"
	"aid-updater/internal/sysctl"
)

type configBackupRecord struct {
	Mode           string `json:"mode"`
	ConfigPath     string `json:"configPath"`
	ConfigFile     string `json:"configFile"`
	DescriptorFile string `json:"descriptorFile,omitempty"`
	CreatedAt      string `json:"createdAt"`
}

func (r *Runner) runConfigValidate(t *Task) error {
	raw, state, err := r.cfg.BuildDeploymentConfig(t.ConfigPath, t.ConfigValues)
	if err != nil {
		return err
	}
	return r.validateRenderedConfiguration(state, raw)
}

func (r *Runner) runConfigApply(t *Task) error {
	raw, state, err := r.cfg.BuildDeploymentConfig(t.ConfigPath, t.ConfigValues)
	if err != nil {
		return err
	}
	if err := r.validateRenderedConfiguration(state, raw); err != nil {
		return err
	}
	record, err := r.backupDeploymentConfiguration()
	if err != nil {
		return fmt.Errorf("备份当前配置失败: %w", err)
	}
	if err := atomicWriteDeploymentFile(state.ConfigPath, raw); err != nil {
		return fmt.Errorf("写入配置失败: %w", err)
	}
	if err := r.cfg.WriteDeploymentDescriptor(state.Mode, state.ConfigPath); err != nil {
		_ = r.restoreDeploymentConfiguration(record)
		return fmt.Errorf("更新配置路径失败: %w", err)
	}
	if err := r.restartWithDeploymentConfiguration(state); err != nil {
		if restoreErr := r.restoreDeploymentConfiguration(record); restoreErr != nil {
			return fmt.Errorf("配置生效失败(%v)，恢复旧配置失败(%v)", err, restoreErr)
		}
		if restartErr := r.restartCurrentDeployment(); restartErr != nil {
			return fmt.Errorf("配置生效失败，旧配置已恢复但服务启动失败: %v", restartErr)
		}
		return fmt.Errorf("配置生效失败，已恢复旧配置: %w", err)
	}
	refreshed, err := r.cfg.RefreshDeployment()
	if err != nil {
		return fmt.Errorf("配置已生效但升级器重新加载失败: %w", err)
	}
	r.reportDeploymentState(refreshed)
	return nil
}

func (r *Runner) runConfigRollback() error {
	record, err := loadConfigBackupRecord(r.configBackupRecordPath())
	if err != nil {
		return fmt.Errorf("没有可恢复的配置备份: %w", err)
	}
	if err := r.restoreDeploymentConfiguration(record); err != nil {
		return err
	}
	if err := r.restartCurrentDeployment(); err != nil {
		return fmt.Errorf("配置已恢复但服务重启失败: %w", err)
	}
	state, err := r.cfg.RefreshDeployment()
	if err != nil {
		return err
	}
	r.reportDeploymentState(state)
	return nil
}

func (r *Runner) validateRenderedConfiguration(state *config.DeploymentState, raw []byte) error {
	if state.Mode != sysctl.ManagerDocker {
		return nil
	}
	if _, err := os.Stat(r.cfg.Deployment.ComposeFile); err != nil {
		return fmt.Errorf("Docker编排文件不可用: %w", err)
	}
	if err := os.MkdirAll(r.cfg.Deployment.AllowedConfigRoot, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(r.cfg.Deployment.AllowedConfigRoot, ".validate-*.env")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(raw); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	cmd := exec.Command("docker", "compose", "--env-file", temporaryPath,
		"-f", r.cfg.Deployment.ComposeFile, "config", "--quiet")
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("Docker配置校验失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	if deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "https") {
		cmd = exec.Command("docker", "compose", "--env-file", temporaryPath,
			"-f", r.cfg.Deployment.ComposeFile, "run", "--rm", "--no-deps",
			"nginx-https", "nginx", "-t")
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("HTTPS证书或Nginx配置校验失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
		}
	}
	return nil
}

func (r *Runner) restartWithDeploymentConfiguration(state *config.DeploymentState) error {
	if state.Mode == sysctl.ManagerDocker {
		if err := prepareDockerServices(state); err != nil {
			return err
		}
		cmd := exec.Command("docker", "compose", "--env-file", state.ConfigPath,
			"-f", r.cfg.Deployment.ComposeFile, "up", "-d")
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("重建Docker服务失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
		}
		if deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "https") {
			if err := waitDockerContainerHealthy("aid-nginx-https", 90*time.Second); err != nil {
				return err
			}
		}
	} else {
		if err := restartManualApplicationWithManager(r.cfg); err != nil {
			return err
		}
	}
	return sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second)
}

var runManualManagerRestartCommand = func(managerScript string, env []string) ([]byte, error) {
	cmd := exec.Command("bash", managerScript, "restart")
	cmd.Env = env
	return cmd.CombinedOutput()
}

// restartManualApplicationWithManager 必须调用当前已安装的新版管理脚本。
// 它会迁移旧 aid-web.service、重写 Nginx 静态站点并执行完整健康检查；
// 当前升级器仍在处理任务，因此必须禁止管理脚本重启升级器自身。
func restartManualApplicationWithManager(cfg *config.Config) error {
	managerScript := strings.TrimSpace(cfg.Deployment.ManagerScript)
	if managerScript == "" {
		return fmt.Errorf("部署管理脚本路径为空")
	}
	env := environmentWithOverride(os.Environ(), "AID_SKIP_UPDATER_RESTART", "1")
	output, err := runManualManagerRestartCommand(managerScript, env)
	if err != nil {
		return fmt.Errorf("重启systemd服务失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func environmentWithOverride(base []string, key, value string) []string {
	prefix := key + "="
	result := make([]string, 0, len(base)+1)
	for _, item := range base {
		if strings.HasPrefix(item, prefix) {
			continue
		}
		result = append(result, item)
	}
	return append(result, prefix+value)
}

// prepareDockerServices 在 Compose 启动前处理可选服务。外部 MySQL 必须先通过
// 版本与 AID 核心表只读校验，才允许停用旧内置容器；失败时上层会恢复旧配置。
func prepareDockerServices(state *config.DeploymentState) error {
	profiles := state.Values["COMPOSE_PROFILES"]
	if !deploymentProfileEnabled(profiles, "mysql") {
		port, _ := strconv.Atoi(state.Values["DB_PORT"])
		database := config.Database{
			Enabled:       true,
			Host:          state.Values["DB_HOST"],
			Port:          port,
			Name:          state.Values["DB_NAME"],
			User:          state.Values["DB_USERNAME"],
			Password:      state.Values["DB_PASSWORD"],
			ClientImage:   "mysql:5.7",
			DockerNetwork: "host",
		}
		version, err := dbexec.Query(database, "SELECT VERSION()")
		if err != nil {
			return fmt.Errorf("外部MySQL连接失败: %w", err)
		}
		version = strings.TrimSpace(version)
		if !strings.HasPrefix(version, "5.7.") {
			return fmt.Errorf("外部MySQL必须为5.7，当前为%s", version)
		}
		coreTables, err := dbexec.Query(database,
			"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('aid_config','sys_user')")
		if err != nil {
			return fmt.Errorf("外部MySQL结构检查失败: %w", err)
		}
		if strings.TrimSpace(coreTables) != "2" {
			return fmt.Errorf("外部MySQL缺少AID核心表，请先完成数据库迁移")
		}
		if err := removeDockerContainer("aid-mysql"); err != nil {
			return fmt.Errorf("停用内置MySQL失败: %w", err)
		}
	}
	if !deploymentProfileEnabled(profiles, "redis") {
		if err := removeDockerContainer("aid-redis"); err != nil {
			return fmt.Errorf("停用内置Redis失败: %w", err)
		}
	}
	if !deploymentProfileEnabled(profiles, "mq") {
		for _, container := range []string{"aid-rocketmq-broker", "aid-rocketmq-nameserver"} {
			if err := removeDockerContainer(container); err != nil {
				return fmt.Errorf("停用内置RocketMQ失败: %w", err)
			}
		}
	}
	if !deploymentProfileEnabled(profiles, "https") {
		if err := removeDockerContainer("aid-nginx-https"); err != nil {
			return fmt.Errorf("停用内置HTTPS失败: %w", err)
		}
	}
	return nil
}

func removeDockerContainer(container string) error {
	inspect := exec.Command("docker", "inspect", container)
	if err := inspect.Run(); err != nil {
		return nil
	}
	remove := exec.Command("docker", "rm", "-f", container)
	if output, err := remove.CombinedOutput(); err != nil {
		return fmt.Errorf("%v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func deploymentProfileEnabled(raw, target string) bool {
	for _, item := range strings.Split(raw, ",") {
		if strings.TrimSpace(item) == target {
			return true
		}
	}
	return false
}

func waitDockerContainerHealthy(container string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		cmd := exec.Command("docker", "inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}", container)
		output, err := cmd.CombinedOutput()
		status := strings.TrimSpace(string(output))
		if err == nil && (status == "healthy" || status == "running") {
			return nil
		}
		if status == "unhealthy" || status == "exited" || status == "dead" {
			return fmt.Errorf("HTTPS容器状态异常: %s", status)
		}
		time.Sleep(2 * time.Second)
	}
	return fmt.Errorf("HTTPS容器健康检查超时")
}

func (r *Runner) restartCurrentDeployment() error {
	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return err
	}
	return r.restartWithDeploymentConfiguration(state)
}

func (r *Runner) backupDeploymentConfiguration() (*configBackupRecord, error) {
	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return nil, err
	}
	backupDir := filepath.Join(r.cfg.BackupDir, "configuration")
	if err := os.MkdirAll(backupDir, 0o700); err != nil {
		return nil, err
	}
	backupFile := filepath.Join(backupDir, "last-config.bak")
	raw, err := os.ReadFile(state.ConfigPath)
	if err != nil {
		return nil, err
	}
	if err := atomicWriteDeploymentFile(backupFile, raw); err != nil {
		return nil, err
	}
	record := &configBackupRecord{
		Mode:       state.Mode,
		ConfigPath: state.ConfigPath,
		ConfigFile: backupFile,
		CreatedAt:  time.Now().Format("2006-01-02 15:04:05"),
	}
	if descriptorRaw, readErr := os.ReadFile(r.cfg.Deployment.DescriptorFile); readErr == nil {
		descriptorBackup := filepath.Join(backupDir, "last-deployment.json.bak")
		if err := atomicWriteDeploymentFile(descriptorBackup, descriptorRaw); err != nil {
			return nil, err
		}
		record.DescriptorFile = descriptorBackup
	}
	recordRaw, _ := json.MarshalIndent(record, "", "  ")
	if err := atomicWriteDeploymentFile(r.configBackupRecordPath(), recordRaw); err != nil {
		return nil, err
	}
	return record, nil
}

func (r *Runner) restoreDeploymentConfiguration(record *configBackupRecord) error {
	raw, err := os.ReadFile(record.ConfigFile)
	if err != nil {
		return err
	}
	if err := atomicWriteDeploymentFile(record.ConfigPath, raw); err != nil {
		return err
	}
	if record.DescriptorFile != "" {
		descriptorRaw, err := os.ReadFile(record.DescriptorFile)
		if err != nil {
			return err
		}
		if err := atomicWriteDeploymentFile(r.cfg.Deployment.DescriptorFile, descriptorRaw); err != nil {
			return err
		}
	} else if err := r.cfg.WriteDeploymentDescriptor(record.Mode, record.ConfigPath); err != nil {
		return err
	}
	return nil
}

func (r *Runner) configBackupRecordPath() string {
	return filepath.Join(r.cfg.BackupDir, "configuration", "last-config.json")
}

func loadConfigBackupRecord(path string) (*configBackupRecord, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	record := &configBackupRecord{}
	if err := json.Unmarshal(raw, record); err != nil {
		return nil, err
	}
	return record, nil
}

func atomicWriteDeploymentFile(path string, raw []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".aid-deploy-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err := temporary.Write(raw); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Chmod(temporaryPath, 0o600); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func (r *Runner) reportDeploymentState(state *config.DeploymentState) {
	if state == nil {
		return
	}
	r.reporter.SetConfiguration(&health.DeploymentConfiguration{
		Mode:              state.Mode,
		ConfigPath:        state.ConfigPath,
		DefaultConfigPath: state.DefaultConfigPath,
		AllowedConfigRoot: state.AllowedConfigRoot,
		Values:            state.SafeValues,
		ConfiguredSecrets: state.ConfiguredSecrets,
	})
}
