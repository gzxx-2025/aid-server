package task

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"aid-updater/internal/config"
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
	return nil
}

func (r *Runner) restartWithDeploymentConfiguration(state *config.DeploymentState) error {
	if state.Mode == sysctl.ManagerDocker {
		cmd := exec.Command("docker", "compose", "--env-file", state.ConfigPath,
			"-f", r.cfg.Deployment.ComposeFile, "up", "-d")
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("重建Docker服务失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
		}
	} else {
		cmd := exec.Command("bash", r.cfg.Deployment.ManagerScript, "restart")
		// 配置任务由升级器自身执行；管理脚本只重启业务服务，不能在任务完成和
		// 回滚状态写入前杀掉当前升级器进程。
		cmd.Env = append(os.Environ(), "AID_SKIP_UPDATER_RESTART=1")
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("重启systemd服务失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
		}
	}
	return sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second)
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
