package task

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"aid-updater/internal/config"
)

type nginxBackup struct {
	Values map[string]string `json:"values"`
	Public string            `json:"public"`
	Admin  string            `json:"admin"`
}

func (r *Runner) nginxRecordPath(pending bool) string {
	name := "nginx-previous.json"
	if pending {
		name = "nginx-pending.json"
	}
	return filepath.Join(r.cfg.BackupDir, name)
}

func (r *Runner) runNginx(t *Task) error {
	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return err
	}
	if state.SafeValues["NGINX_MANAGEMENT_AVAILABLE"] != "true" {
		return fmt.Errorf("请先升级安装脚本并接入受管Nginx配置")
	}
	if t.NginxRevision == "" || t.NginxRevision != state.SafeValues["NGINX_REVISION"] {
		return fmt.Errorf("配置已变化，请刷新后重试")
	}
	changes := t.ConfigValues
	if t.Action == ActionNginxRollback {
		previous, err := r.loadNginxRecord(false)
		if err != nil {
			return fmt.Errorf("没有可恢复的Nginx配置")
		}
		changes = previous.Values
	}
	allowed := map[string]bool{}
	for _, key := range config.NginxKeys {
		allowed[key] = true
	}
	for key := range changes {
		if !allowed[key] {
			return fmt.Errorf("非Nginx配置项不允许在此修改")
		}
	}
	// Keep the entire source file private and unchanged during candidate validation,
	// including unrelated settings and comments edited by an external operator.
	originalRaw, err := os.ReadFile(state.ConfigPath)
	if err != nil {
		return err
	}
	raw, candidate, err := r.cfg.BuildDeploymentConfig(state.ConfigPath, changes)
	if err != nil {
		return err
	}
	directory := filepath.Join(r.cfg.Deployment.AllowedConfigRoot, "nginx-managed")
	for _, kind := range []string{"public", "admin"} {
		if _, err := r.cfg.ResolveDeploymentConfigPath(filepath.Join(directory, kind+".conf")); err != nil {
			return err
		}
	}
	stage, err := os.MkdirTemp(directory, ".check-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	generated := map[string][]byte{}
	for _, kind := range []string{"public", "admin"} {
		output, err := r.renderNginx(candidate, kind)
		if err != nil {
			return err
		}
		generated[kind] = output
		if err := os.WriteFile(filepath.Join(stage, kind+".conf"), output, 0644); err != nil {
			return err
		}
	}
	includeRoot := stage
	if state.Mode == "docker" {
		includeRoot = "/etc/nginx/aid-managed/" + filepath.Base(stage)
	}
	wrapper := "error_log stderr; events {} http { server { server_name public.invalid; include " + includeRoot + "/public.conf; } server { server_name admin.invalid; include " + includeRoot + "/admin.conf; } }\n"
	if err := os.WriteFile(filepath.Join(stage, "nginx.conf"), []byte(wrapper), 0644); err != nil {
		return err
	}
	if err := r.nginxCommand(state, "test", includeRoot+"/nginx.conf"); err != nil {
		return err
	}
	// Probe from the gateway network before changing the API origin used by this admin page.
	if err := r.nginxCommand(candidate, "probe", ""); err != nil {
		return err
	}
	if t.Action == ActionNginxValidate {
		return nil
	}
	current, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return err
	}
	if current.ConfigPath != state.ConfigPath || current.SafeValues["NGINX_REVISION"] != t.NginxRevision {
		return fmt.Errorf("校验期间配置已变化，请重新读取")
	}
	currentRaw, err := os.ReadFile(current.ConfigPath)
	if err != nil {
		return err
	}
	if !bytes.Equal(originalRaw, currentRaw) {
		return fmt.Errorf("校验期间部署配置已变化，请重新读取")
	}
	backup := &nginxBackup{Values: config.NginxValues(state.Mode, state.Values), Public: state.SafeValues["NGINX_PUBLIC_PREVIEW"], Admin: state.SafeValues["NGINX_ADMIN_PREVIEW"]}
	journal, _ := json.Marshal(backup)
	if err := atomicWriteDeploymentFile(r.nginxRecordPath(true), journal); err != nil {
		return err
	}
	applyErr := func() error {
		for _, kind := range []string{"public", "admin"} {
			if err := writeNginxInclude(filepath.Join(directory, kind+".conf"), generated[kind]); err != nil {
				return err
			}
		}
		if err := r.nginxCommand(state, "test", ""); err != nil {
			return err
		}
		latestRaw, err := os.ReadFile(state.ConfigPath)
		if err != nil {
			return err
		}
		if !bytes.Equal(originalRaw, latestRaw) {
			return fmt.Errorf("应用期间部署配置已变化，请重新读取")
		}
		if err := atomicWriteDeploymentFile(state.ConfigPath, raw); err != nil {
			return err
		}
		return r.nginxCommand(state, "reload", "")
	}()
	if applyErr != nil {
		if restoreErr := r.restoreNginx(backup); restoreErr != nil {
			return fmt.Errorf("Nginx应用失败且自动恢复未完成；保留恢复记录: %v / %v", applyErr, restoreErr)
		}
		if err := os.Remove(r.nginxRecordPath(true)); err != nil {
			return err
		}
		return fmt.Errorf("Nginx应用失败，已恢复旧配置: %w", applyErr)
	}
	if err := atomicWriteDeploymentFile(r.nginxRecordPath(false), journal); err != nil {
		return err
	}
	if err := os.Remove(r.nginxRecordPath(true)); err != nil {
		return err
	}
	refreshed, err := r.cfg.ReadDeploymentState()
	if err == nil {
		r.reportDeploymentState(refreshed)
	}
	return err
}

func writeNginxInclude(path string, raw []byte) error {
	if _, err := config.ReadManagedNginx(path); err != nil {
		return err
	}
	if err := atomicWriteDeploymentFile(path, raw); err != nil {
		return err
	}
	return os.Chmod(path, 0644)
}

func (r *Runner) renderNginx(state *config.DeploymentState, kind string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	script := filepath.Join(filepath.Dir(r.cfg.Deployment.ManagerScript), "nginx", "render.sh")
	cmd := exec.CommandContext(ctx, "sh", script, kind)
	env := environmentWithOverride(os.Environ(), "NGINX_DEPLOY_MODE", state.Mode)
	for key, value := range config.NginxValues(state.Mode, state.Values) {
		env = environmentWithOverride(env, key, value)
	}
	cmd.Env = env
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("Nginx模板生成失败: %w", err)
	}
	if len(out) > 16384 || !strings.HasPrefix(string(out), "# AID_MANAGED_NGINX_INCLUDE=1\n") {
		return nil, fmt.Errorf("Nginx模板输出无效")
	}
	return out, nil
}

func (r *Runner) nginxCommand(state *config.DeploymentState, action, path string) error {
	if state.Mode != "docker" {
		if action == "probe" {
			return runNginxCommand("curl", "--fail", "--silent", "--show-error", "--max-time", "10", "--output", os.DevNull,
				"--", config.NginxValues(state.Mode, state.Values)["NGINX_BACKEND_ORIGIN"]+"/seo/public/robots.txt")
		}
		args := []string{r.cfg.Deployment.ManagerScript, "__nginx-" + action}
		if path != "" {
			args = append(args, path)
		}
		return runNginxCommand("bash", args...)
	}
	containers := []string{"aid-nginx"}
	if deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "https") {
		containers = append(containers, "aid-nginx-https")
	}
	for _, container := range containers {
		// Do not control a same-named container owned by another installation.
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		out, err := exec.CommandContext(ctx, "docker", "inspect", "--format", `{{index .Config.Labels "com.aid.data_root"}}`, container).CombinedOutput()
		cancel()
		if err != nil || strings.TrimSpace(string(out)) != state.Values["DATA_ROOT"] {
			return fmt.Errorf("Nginx容器归属校验失败")
		}
		ctx, cancel = context.WithTimeout(context.Background(), 10*time.Second)
		out, err = exec.CommandContext(ctx, "docker", "inspect", "--format", `{{json .Mounts}}`, container).CombinedOutput()
		cancel()
		var mounts []struct {
			Source      string
			Destination string
			RW          bool
		}
		if err != nil || json.Unmarshal(out, &mounts) != nil {
			return fmt.Errorf("无法核验Nginx挂载")
		}
		mounted := false
		for _, mount := range mounts {
			if mount.Destination == "/etc/nginx/aid-managed" && mount.Source == filepath.Join(state.Values["DATA_ROOT"], "config", "nginx-managed") && mount.RW {
				mounted = true
			}
		}
		if !mounted {
			return fmt.Errorf("请先升级并重建Nginx网关目录挂载")
		}
		args := []string{"exec", container, "nginx"}
		if action == "probe" {
			args = []string{"exec", container, "wget", "-q", "-T", "10", "-O", "/dev/null", config.NginxValues(state.Mode, state.Values)["NGINX_BACKEND_ORIGIN"] + "/seo/public/robots.txt"}
		} else if action == "test" {
			args = append(args, "-t")
			if path != "" {
				args = append(args, "-c", path)
			}
		} else {
			args = append(args, "-s", "reload")
		}
		if err := runNginxCommand("docker", args...); err != nil {
			return err
		}
	}
	return nil
}

func runNginxCommand(name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(out))
		if len(message) > 1200 {
			message = message[:1200]
		}
		return fmt.Errorf("Nginx校验或重载失败: %v %s", err, message)
	}
	return nil
}

func (r *Runner) loadNginxRecord(pending bool) (*nginxBackup, error) {
	path := r.nginxRecordPath(pending)
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > 40000 {
		return nil, fmt.Errorf("Nginx备份无效")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var record nginxBackup
	if err := json.Unmarshal(raw, &record); err != nil {
		return nil, err
	}
	if len(record.Values) != len(config.NginxKeys) {
		return nil, fmt.Errorf("Nginx备份字段不完整")
	}
	allowed := map[string]bool{}
	for _, key := range config.NginxKeys {
		allowed[key] = true
	}
	for key := range record.Values {
		if !allowed[key] {
			return nil, fmt.Errorf("Nginx备份包含其他配置字段")
		}
	}
	if !strings.HasPrefix(record.Public, "# AID_MANAGED_NGINX_INCLUDE=1\n") || !strings.HasPrefix(record.Admin, "# AID_MANAGED_NGINX_INCLUDE=1\n") {
		return nil, fmt.Errorf("Nginx备份标记无效")
	}
	return &record, nil
}

func (r *Runner) restoreNginx(record *nginxBackup) error {
	raw, state, err := r.cfg.BuildDeploymentConfig("", record.Values)
	if err != nil {
		return err
	}
	for kind, content := range map[string]string{"public": record.Public, "admin": record.Admin} {
		path := filepath.Join(r.cfg.Deployment.AllowedConfigRoot, "nginx-managed", kind+".conf")
		if _, err := r.cfg.ResolveDeploymentConfigPath(path); err != nil {
			return err
		}
		if err := writeNginxInclude(path, []byte(content)); err != nil {
			return err
		}
	}
	if err := atomicWriteDeploymentFile(state.ConfigPath, raw); err != nil {
		return err
	}
	if err := r.nginxCommand(state, "test", ""); err != nil {
		return err
	}
	return r.nginxCommand(state, "reload", "")
}

func (r *Runner) recoverNginx() error {
	record, err := r.loadNginxRecord(true)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := r.restoreNginx(record); err != nil {
		return err
	}
	return os.Remove(r.nginxRecordPath(true))
}

// Refresh templates on product upgrades while retaining configured values.
func (r *Runner) refreshManagedNginx(state *config.DeploymentState) error {
	if !config.SupportsManagedNginx(r.cfg.Deployment.ManagerScript) {
		return nil
	}
	if !fileExists(filepath.Join(filepath.Dir(r.cfg.Deployment.ManagerScript), "nginx", "render.sh")) {
		return nil
	}
	directory := filepath.Join(r.cfg.Deployment.AllowedConfigRoot, "nginx-managed")
	if err := os.MkdirAll(directory, 0755); err != nil {
		return err
	}
	for _, kind := range []string{"public", "admin"} {
		path := filepath.Join(directory, kind+".conf")
		if _, err := r.cfg.ResolveDeploymentConfigPath(path); err != nil {
			return err
		}
		if _, err := os.Lstat(path); err == nil {
			if _, err := config.ReadManagedNginx(path); err != nil {
				return err
			}
		} else if !os.IsNotExist(err) {
			return err
		}
		raw, err := r.renderNginx(state, kind)
		if err != nil {
			return err
		}
		if err := atomicWriteDeploymentFile(path, raw); err != nil {
			return err
		}
		if err := os.Chmod(path, 0644); err != nil {
			return err
		}
	}
	return nil
}
