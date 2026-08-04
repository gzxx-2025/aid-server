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
	"DB_HOST": true, "DB_PORT": true, "DB_NAME": true, "DB_USERNAME": true, "DB_PASSWORD": true,
	"REDIS_HOST": true, "REDIS_PORT": true, "REDIS_USERNAME": true,
	"REDIS_PASSWORD": true, "REDIS_DATABASE": true,
	"TOKEN_SECRET": true, "JAVA_OPTS": true,
	"ROCKETMQ_ENABLED": true, "ROCKETMQ_NAMESERVER": true,
	"ROCKETMQ_ACCESS_KEY": true, "ROCKETMQ_SECRET_KEY": true,
	"HTTPS_PORT": true, "HTTPS_PUBLIC_DOMAIN": true, "HTTPS_ADMIN_DOMAIN": true,
	"HTTPS_CERT_PATH": true, "HTTPS_KEY_PATH": true,
}

var dockerDeploymentKeys = map[string]bool{
	"DATA_ROOT": true, "MYSQL_ROOT_PASSWORD": true, "MYSQL_PORT": true,
	"MYSQL_BUFFER_POOL": true, "MYSQL_MAX_CONNECTIONS": true,
	"REDIS_MAXMEMORY": true, "REDIS_MAXMEMORY_POLICY": true,
	"WEB_NODE_OPTIONS": true, "COMPOSE_PROFILES": true,
	"MQ_NAMESRV_JAVA_OPTS": true, "MQ_BROKER_JAVA_OPTS": true,
}

var manualDeploymentKeys = map[string]bool{
	"DATA_ROOT": true, "HTTPS_ENABLED": true,
}

var secretDeploymentKeys = map[string]bool{
	"MYSQL_ROOT_PASSWORD": true,
	"DB_PASSWORD":         true,
	"REDIS_PASSWORD":      true,
	"TOKEN_SECRET":        true,
	"ROCKETMQ_ACCESS_KEY": true,
	"ROCKETMQ_SECRET_KEY": true,
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
	normalizeLegacyDockerDatabase(mode, values)
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

// normalizeLegacyDockerDatabase 兼容旧版“固定内置 MySQL”的配置。只有 DB_HOST
// 缺失时才推断为内置模式；新配置明确填写外部地址后绝不会被自动加入 mysql Profile。
func normalizeLegacyDockerDatabase(mode string, values map[string]string) {
	if mode != "docker" {
		return
	}
	if strings.TrimSpace(values["DB_HOST"]) != "" {
		if strings.TrimSpace(values["DB_PORT"]) == "" {
			values["DB_PORT"] = "3306"
		}
		return
	}
	values["DB_HOST"] = "mysql"
	values["DB_PORT"] = "3306"
	profiles, _ := parseComposeProfiles(values["COMPOSE_PROFILES"])
	if !profiles["mysql"] {
		if strings.TrimSpace(values["COMPOSE_PROFILES"]) == "" {
			values["COMPOSE_PROFILES"] = "mysql"
		} else {
			values["COMPOSE_PROFILES"] += ",mysql"
		}
	}
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
		c.Database.Name = valueOr(values, "DB_NAME", "aid")
		profiles, profileErr := parseComposeProfiles(values["COMPOSE_PROFILES"])
		if profileErr != nil {
			return nil, profileErr
		}
		if profiles["mysql"] {
			c.Database.Host = "127.0.0.1"
			c.Database.Port = 3306
			c.Database.User = "root"
			c.Database.Password = values["MYSQL_ROOT_PASSWORD"]
			c.Database.ExecContainer = "aid-mysql"
			c.Database.ClientImage = ""
			c.Database.DockerNetwork = ""
		} else {
			c.Database.Host = values["DB_HOST"]
			c.Database.Port, _ = strconv.Atoi(valueOr(values, "DB_PORT", "3306"))
			c.Database.User = values["DB_USERNAME"]
			c.Database.Password = values["DB_PASSWORD"]
			c.Database.ExecContainer = ""
			c.Database.ClientImage = "mysql:5.7"
			c.Database.DockerNetwork = "host"
		}
	} else {
		c.Database.Enabled = true
		c.Database.Host = valueOr(values, "DB_HOST", "127.0.0.1")
		c.Database.Port, _ = strconv.Atoi(valueOr(values, "DB_PORT", "3306"))
		c.Database.Name = valueOr(values, "DB_NAME", "aid")
		c.Database.User = valueOr(values, "DB_USERNAME", "root")
		c.Database.Password = values["DB_PASSWORD"]
		c.Database.ExecContainer = ""
		c.Database.ClientImage = ""
		c.Database.DockerNetwork = ""
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
	required := []string{"HTTP_PORT", "ADMIN_PORT", "BACKEND_PORT", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "TOKEN_SECRET", "REDIS_HOST", "REDIS_PORT"}
	if mode == "docker" {
		required = append(required, "DATA_ROOT")
	}
	for _, key := range required {
		if strings.TrimSpace(values[key]) == "" {
			return fmt.Errorf("配置项 %s 不能为空", key)
		}
	}
	if !alphaNumericUnderscore(strings.TrimSpace(values["DB_NAME"])) {
		return fmt.Errorf("DB_NAME仅允许字母数字和下划线")
	}
	for _, key := range []string{"HTTP_PORT", "ADMIN_PORT", "BACKEND_PORT", "REDIS_PORT", "DB_PORT", "MYSQL_PORT", "HTTPS_PORT"} {
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
	if enabled := values["HTTPS_ENABLED"]; enabled != "" && enabled != "true" && enabled != "false" {
		return fmt.Errorf("HTTPS_ENABLED 只支持 true 或 false")
	}
	if database := strings.TrimSpace(values["REDIS_DATABASE"]); database != "" {
		index, err := strconv.Atoi(database)
		if err != nil || index < 0 {
			return fmt.Errorf("REDIS_DATABASE 必须是非负整数")
		}
	}
	accessKey := strings.TrimSpace(values["ROCKETMQ_ACCESS_KEY"])
	secretKey := strings.TrimSpace(values["ROCKETMQ_SECRET_KEY"])
	if (accessKey == "") != (secretKey == "") {
		return fmt.Errorf("RocketMQ ACL凭证必须同时填写")
	}
	if accessKey != "" && (!alphaNumeric(accessKey) || !alphaNumeric(secretKey)) {
		return fmt.Errorf("RocketMQ ACL凭证仅允许字母和数字")
	}
	if values["ROCKETMQ_ENABLED"] == "true" && strings.TrimSpace(values["ROCKETMQ_NAMESERVER"]) == "" {
		return fmt.Errorf("RocketMQ地址不能为空")
	}
	if mode == "docker" {
		profiles, err := parseComposeProfiles(values["COMPOSE_PROFILES"])
		if err != nil {
			return err
		}
		if profiles["redis"] {
			username := strings.TrimSpace(values["REDIS_USERNAME"])
			if username != "" && username != "default" {
				return fmt.Errorf("内置Redis仅支持default用户")
			}
		}
		if profiles["mysql"] {
			if strings.TrimSpace(values["MYSQL_ROOT_PASSWORD"]) == "" || strings.TrimSpace(values["MYSQL_PORT"]) == "" {
				return fmt.Errorf("内置MySQL必须配置root密码和映射端口")
			}
			if values["DB_HOST"] != "mysql" || values["DB_PORT"] != "3306" {
				return fmt.Errorf("内置MySQL地址必须为mysql:3306")
			}
		} else {
			host := strings.TrimSpace(values["DB_HOST"])
			if host == "mysql" || host == "localhost" || host == "127.0.0.1" || host == "::1" {
				return fmt.Errorf("外部MySQL地址不能使用容器回环地址")
			}
		}
		if profiles["https"] {
			if err := validateHTTPSValues(values); err != nil {
				return err
			}
		}
	} else if values["HTTPS_ENABLED"] == "true" {
		if strings.TrimSpace(values["DATA_ROOT"]) == "" {
			return fmt.Errorf("启用HTTPS时DATA_ROOT不能为空")
		}
		if err := validateHTTPSValues(values); err != nil {
			return err
		}
	}
	return nil
}

func alphaNumeric(value string) bool {
	if value == "" {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') {
			continue
		}
		return false
	}
	return true
}

func alphaNumericUnderscore(value string) bool {
	if value == "" {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || char == '_' {
			continue
		}
		return false
	}
	return true
}

func parseComposeProfiles(raw string) (map[string]bool, error) {
	profiles := make(map[string]bool)
	for _, item := range strings.Split(raw, ",") {
		profile := strings.TrimSpace(item)
		if profile == "" {
			continue
		}
		if profile != "mysql" && profile != "redis" && profile != "mq" && profile != "https" {
			return nil, fmt.Errorf("COMPOSE_PROFILES仅支持mysql、redis、mq、https")
		}
		profiles[profile] = true
	}
	return profiles, nil
}

func validateHTTPSValues(values map[string]string) error {
	httpsPort := strings.TrimSpace(valueOr(values, "HTTPS_PORT", "443"))
	if httpsPort == strings.TrimSpace(values["HTTP_PORT"]) || httpsPort == strings.TrimSpace(values["ADMIN_PORT"]) {
		return fmt.Errorf("HTTPS端口与HTTP端口冲突")
	}
	publicDomain := strings.TrimSpace(values["HTTPS_PUBLIC_DOMAIN"])
	adminDomain := strings.TrimSpace(values["HTTPS_ADMIN_DOMAIN"])
	if !validServerName(publicDomain) || !validServerName(adminDomain) {
		return fmt.Errorf("HTTPS域名格式错误")
	}
	if publicDomain == adminDomain {
		return fmt.Errorf("HTTPS用户端和管理端域名不能相同")
	}
	dataRootValue := strings.TrimSpace(values["DATA_ROOT"])
	if !filepath.IsAbs(dataRootValue) {
		return fmt.Errorf("DATA_ROOT必须是绝对路径")
	}
	dataRoot := filepath.Clean(dataRootValue)
	sslRoot := filepath.Join(dataRoot, "config", "ssl")
	for _, key := range []string{"HTTPS_CERT_PATH", "HTTPS_KEY_PATH"} {
		if err := validateTLSFile(values[key], sslRoot); err != nil {
			return fmt.Errorf("%s校验失败: %w", key, err)
		}
	}
	return nil
}

func validServerName(value string) bool {
	if value == "" || strings.ContainsAny(value, " /\\:\t\r\n") {
		return false
	}
	first := value[0]
	last := value[len(value)-1]
	if !((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || (first >= '0' && first <= '9')) ||
		!((last >= 'a' && last <= 'z') || (last >= 'A' && last <= 'Z') || (last >= '0' && last <= '9')) {
		return false
	}
	for index, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || char == '.' || (char == '-' && index > 0) {
			continue
		}
		return false
	}
	return true
}

func validateTLSFile(path, sslRoot string) error {
	path = filepath.Clean(strings.TrimSpace(path))
	if !filepath.IsAbs(path) {
		return fmt.Errorf("必须使用绝对路径")
	}
	relative, err := filepath.Rel(filepath.Clean(sslRoot), path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return fmt.Errorf("只能位于%s", sslRoot)
	}
	if err := rejectSymlinkComponents(path); err != nil {
		return err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return fmt.Errorf("文件不存在")
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return fmt.Errorf("必须是非软链接普通文件")
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
