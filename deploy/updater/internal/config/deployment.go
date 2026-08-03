package config

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// DeploymentState 是升级器读取到的部署配置快照。Values 仅在升级器进程内使用；
// SafeValues 与 ConfiguredSecrets 才允许写入健康文件供后台展示。
type DeploymentState struct {
	Mode              string
	ConfigPath        string
	DefaultConfigPath string
	AllowedConfigRoot string
	Values            map[string]string
	SafeValues        map[string]string
	ConfiguredSecrets []string
}

type deploymentDescriptor struct {
	Mode       string `json:"mode"`
	ConfigPath string `json:"configPath"`
}

var commonDeploymentKeys = map[string]bool{
	"HTTP_PORT": true, "ADMIN_PORT": true, "BACKEND_PORT": true,
	"DB_NAME": true, "DB_USERNAME": true, "DB_PASSWORD": true,
	"REDIS_HOST": true, "REDIS_PORT": true, "REDIS_PASSWORD": true,
	"TOKEN_SECRET": true, "JAVA_OPTS": true,
	"ROCKETMQ_ENABLED": true, "ROCKETMQ_NAMESERVER": true,
}

var dockerDeploymentKeys = map[string]bool{
	"DATA_ROOT": true, "MYSQL_ROOT_PASSWORD": true, "MYSQL_PORT": true,
	"MYSQL_BUFFER_POOL": true, "MYSQL_MAX_CONNECTIONS": true,
	"REDIS_MAXMEMORY": true, "REDIS_MAXMEMORY_POLICY": true,
	"WEB_NODE_OPTIONS": true, "COMPOSE_PROFILES": true,
	"MQ_NAMESRV_JAVA_OPTS": true, "MQ_BROKER_JAVA_OPTS": true,
}

var manualDeploymentKeys = map[string]bool{
	"DB_HOST": true, "DB_PORT": true,
}

var secretDeploymentKeys = map[string]bool{
	"MYSQL_ROOT_PASSWORD": true,
	"DB_PASSWORD":         true,
	"REDIS_PASSWORD":      true,
	"TOKEN_SECRET":        true,
}

// ReadDeploymentState 从唯一配置真源读取当前配置，并只生成不含密钥的页面快照。
func (c *Config) ReadDeploymentState() (*DeploymentState, error) {
	mode := c.Install.ServiceManager
	configPath := c.Deployment.ConfigPath
	if descriptor, err := c.readDeploymentDescriptor(); err != nil {
		return nil, err
	} else if descriptor != nil {
		if !strings.EqualFold(descriptor.Mode, mode) {
			return nil, fmt.Errorf("部署描述方式与升级器配置不一致")
		}
		configPath = descriptor.ConfigPath
	}
	resolved, err := c.ResolveDeploymentConfigPath(configPath)
	if err != nil {
		return nil, err
	}
	values, err := readEnvFile(resolved)
	if err != nil {
		return nil, err
	}
	if err := validateDeploymentValues(mode, values); err != nil {
		return nil, err
	}
	safe := make(map[string]string)
	configuredSecrets := make([]string, 0)
	for key, value := range values {
		if !isAllowedDeploymentKey(mode, key) {
			continue
		}
		if secretDeploymentKeys[key] {
			if strings.TrimSpace(value) != "" {
				configuredSecrets = append(configuredSecrets, key)
			}
			continue
		}
		safe[key] = value
	}
	sort.Strings(configuredSecrets)
	return &DeploymentState{
		Mode:              mode,
		ConfigPath:        resolved,
		DefaultConfigPath: c.Deployment.DefaultConfigPath,
		AllowedConfigRoot: c.Deployment.AllowedConfigRoot,
		Values:            values,
		SafeValues:        safe,
		ConfiguredSecrets: configuredSecrets,
	}, nil
}

// RefreshDeployment 重新读取部署配置并同步升级器数据库连接，保证管理员修改配置后
// 后续增量 SQL 与备份使用的仍是同一份配置。
func (c *Config) RefreshDeployment() (*DeploymentState, error) {
	state, err := c.ReadDeploymentState()
	if err != nil {
		return nil, err
	}
	c.Deployment.ConfigPath = state.ConfigPath
	values := state.Values
	if state.Mode == "docker" {
		c.Database.Enabled = true
		c.Database.Host = "127.0.0.1"
		c.Database.Port = 3306
		c.Database.Name = valueOr(values, "DB_NAME", "aid")
		c.Database.User = "root"
		c.Database.Password = values["MYSQL_ROOT_PASSWORD"]
		c.Database.ExecContainer = "aid-mysql"
	} else {
		c.Database.Enabled = true
		c.Database.Host = valueOr(values, "DB_HOST", "127.0.0.1")
		c.Database.Port, _ = strconv.Atoi(valueOr(values, "DB_PORT", "3306"))
		c.Database.Name = valueOr(values, "DB_NAME", "aid")
		c.Database.User = valueOr(values, "DB_USERNAME", "root")
		c.Database.Password = values["DB_PASSWORD"]
		c.Database.ExecContainer = ""
		// 手动部署的监听端口可以在后台配置页调整。健康检查必须随唯一配置真源
		// 一起刷新，否则端口变更后升级器会误判启动失败并触发回滚。
		backendPort := valueOr(values, "BACKEND_PORT", "8080")
		c.Install.HealthCheckURL = "http://127.0.0.1:" + backendPort
	}
	return state, nil
}

// BuildDeploymentConfig 将页面提交的白名单字段合并进现有配置文本，保留注释和未知扩展项。
func (c *Config) BuildDeploymentConfig(targetPath string, changes map[string]string) ([]byte, *DeploymentState, error) {
	state, err := c.ReadDeploymentState()
	if err != nil {
		return nil, nil, err
	}
	resolved, err := c.ResolveDeploymentConfigPath(targetPath)
	if err != nil {
		return nil, nil, err
	}
	for key, value := range changes {
		if !isAllowedDeploymentKey(state.Mode, key) {
			return nil, nil, fmt.Errorf("不支持修改配置项: %s", key)
		}
		if strings.ContainsAny(value, "\r\n\x00") {
			return nil, nil, fmt.Errorf("配置项 %s 包含非法字符", key)
		}
		if secretDeploymentKeys[key] && strings.ContainsAny(value, " #\"'$\\") {
			return nil, nil, fmt.Errorf("配置项 %s 包含不支持的密钥字符", key)
		}
		state.Values[key] = value
	}
	if err := validateDeploymentValues(state.Mode, state.Values); err != nil {
		return nil, nil, err
	}
	raw, err := mergeEnvFile(state.ConfigPath, state.Values, changes)
	if err != nil {
		return nil, nil, err
	}
	state.ConfigPath = resolved
	return raw, state, nil
}

// ResolveDeploymentConfigPath 只允许默认路径或数据目录下专用配置目录，且拒绝软链接。
func (c *Config) ResolveDeploymentConfigPath(requested string) (string, error) {
	requested = strings.TrimSpace(requested)
	if requested == "" {
		requested = c.Deployment.ConfigPath
	}
	abs, err := filepath.Abs(requested)
	if err != nil {
		return "", fmt.Errorf("配置文件路径非法: %w", err)
	}
	abs = filepath.Clean(abs)
	defaultPath, _ := filepath.Abs(c.Deployment.DefaultConfigPath)
	allowedRoot, _ := filepath.Abs(c.Deployment.AllowedConfigRoot)
	allowedPrefix := filepath.Clean(allowedRoot) + string(os.PathSeparator)
	if abs != filepath.Clean(defaultPath) && !strings.HasPrefix(abs, allowedPrefix) {
		return "", fmt.Errorf("配置文件只能使用默认路径或位于 %s", allowedRoot)
	}
	base := filepath.Base(abs)
	if base == "." || base == string(os.PathSeparator) || (!strings.HasSuffix(base, ".env") && !strings.HasSuffix(base, ".conf")) {
		return "", fmt.Errorf("配置文件只支持 .env 或 .conf")
	}
	if err := rejectSymlinkComponents(abs); err != nil {
		return "", err
	}
	return abs, nil
}

// WriteDeploymentDescriptor 原子更新不含密钥的部署指针。
func (c *Config) WriteDeploymentDescriptor(mode, configPath string) error {
	descriptor := deploymentDescriptor{Mode: mode, ConfigPath: configPath}
	raw, err := json.MarshalIndent(descriptor, "", "  ")
	if err != nil {
		return err
	}
	return atomicWriteConfig(c.Deployment.DescriptorFile, raw, 0o600)
}

func (c *Config) readDeploymentDescriptor() (*deploymentDescriptor, error) {
	raw, err := os.ReadFile(c.Deployment.DescriptorFile)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读取部署描述失败: %w", err)
	}
	descriptor := &deploymentDescriptor{}
	if err := json.Unmarshal(raw, descriptor); err != nil {
		return nil, fmt.Errorf("解析部署描述失败: %w", err)
	}
	return descriptor, nil
}

func readEnvFile(path string) (map[string]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("读取部署配置失败: %w", err)
	}
	defer file.Close()
	values := make(map[string]string)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			return nil, fmt.Errorf("部署配置存在非法行")
		}
		values[strings.TrimSpace(parts[0])] = parts[1]
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("读取部署配置失败: %w", err)
	}
	return values, nil
}

func mergeEnvFile(sourcePath string, allValues, changes map[string]string) ([]byte, error) {
	raw, err := os.ReadFile(sourcePath)
	if err != nil {
		return nil, fmt.Errorf("读取部署配置失败: %w", err)
	}
	lines := strings.Split(strings.ReplaceAll(string(raw), "\r\n", "\n"), "\n")
	written := make(map[string]bool)
	for index, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		parts := strings.SplitN(trimmed, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		if _, changed := changes[key]; changed {
			lines[index] = key + "=" + allValues[key]
			written[key] = true
		}
	}
	keys := make([]string, 0)
	for key := range changes {
		if !written[key] {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	for _, key := range keys {
		lines = append(lines, key+"="+allValues[key])
	}
	return []byte(strings.TrimRight(strings.Join(lines, "\n"), "\n") + "\n"), nil
}

func validateDeploymentValues(mode string, values map[string]string) error {
	required := []string{"HTTP_PORT", "ADMIN_PORT", "BACKEND_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "TOKEN_SECRET", "REDIS_HOST", "REDIS_PORT"}
	if mode == "docker" {
		required = append(required, "DATA_ROOT", "MYSQL_ROOT_PASSWORD", "MYSQL_PORT")
	} else {
		required = append(required, "DB_HOST", "DB_PORT")
	}
	for _, key := range required {
		if strings.TrimSpace(values[key]) == "" {
			return fmt.Errorf("配置项 %s 不能为空", key)
		}
	}
	for _, key := range []string{"HTTP_PORT", "ADMIN_PORT", "BACKEND_PORT", "REDIS_PORT", "DB_PORT", "MYSQL_PORT"} {
		value := strings.TrimSpace(values[key])
		if value == "" {
			continue
		}
		port, err := strconv.Atoi(value)
		if err != nil || port < 1 || port > 65535 {
			return fmt.Errorf("配置项 %s 端口范围错误", key)
		}
	}
	if enabled := values["ROCKETMQ_ENABLED"]; enabled != "" && enabled != "true" && enabled != "false" {
		return fmt.Errorf("ROCKETMQ_ENABLED 只支持 true 或 false")
	}
	return nil
}

func isAllowedDeploymentKey(mode, key string) bool {
	return commonDeploymentKeys[key] || (mode == "docker" && dockerDeploymentKeys[key]) ||
		(mode == "systemd" && manualDeploymentKeys[key])
}

func valueOr(values map[string]string, key, fallback string) string {
	if value := strings.TrimSpace(values[key]); value != "" {
		return value
	}
	return fallback
}

func rejectSymlinkComponents(path string) error {
	current := filepath.Clean(path)
	for {
		info, err := os.Lstat(current)
		if err == nil && info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("配置路径禁止使用软链接")
		}
		if err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("检查配置路径失败: %w", err)
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return nil
}

func atomicWriteConfig(path string, raw []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".aid-config-*.tmp")
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
	if err := os.Chmod(temporaryPath, mode); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}
