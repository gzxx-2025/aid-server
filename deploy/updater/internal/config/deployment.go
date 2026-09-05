package config

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
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
	"NGINX_BACKEND_ORIGIN": true, "NGINX_MAX_BODY_MB": true, "NGINX_READ_TIMEOUT_SECONDS": true, "NGINX_CONNECT_TIMEOUT_SECONDS": true, "NGINX_EXTRA_DIRECTIVES": true,
	"HTTP_PORT": true, "ADMIN_PORT": true, "BACKEND_PORT": true,
	"DB_HOST": true, "DB_PORT": true, "DB_NAME": true, "DB_USERNAME": true, "DB_PASSWORD": true,
	"REDIS_HOST": true, "REDIS_PORT": true, "REDIS_USERNAME": true,
	"REDIS_PASSWORD": true, "REDIS_DATABASE": true,
	"TOKEN_SECRET": true, "JAVA_OPTS": true, "DEPENDENCY_INSTALL_MODE": true, "DEPENDENCY_REGION": true,
	"DOCKER_MIRRORS": true, "DOWNLOAD_TIMEOUT_SECONDS": true,
	"ROCKETMQ_ENABLED": true, "ROCKETMQ_NAMESERVER": true,
	"ROCKETMQ_ACCESS_KEY": true, "ROCKETMQ_SECRET_KEY": true,
	"ROCKETMQ_FLUSH_DISK_TYPE": true,
	"HTTPS_PORT":               true, "HTTPS_PUBLIC_DOMAIN": true, "HTTPS_ADMIN_DOMAIN": true,
	"HTTPS_CERT_PATH": true, "HTTPS_KEY_PATH": true,
}

var dockerDeploymentKeys = map[string]bool{
	"DATA_ROOT": true, "MYSQL_ROOT_PASSWORD": true, "MYSQL_PORT": true,
	"MYSQL_BUFFER_POOL": true, "MYSQL_MAX_CONNECTIONS": true,
	"REDIS_MAXMEMORY": true, "REDIS_MAXMEMORY_POLICY": true,
	"COMPOSE_PROFILES":     true,
	"MQ_NAMESRV_JAVA_OPTS": true, "MQ_BROKER_JAVA_OPTS": true,
}

var manualDeploymentKeys = map[string]bool{
	"DATA_ROOT": true, "HTTPS_ENABLED": true, "MYSQL_ROOT_PASSWORD": true,
}

var secretDeploymentKeys = map[string]bool{
	"MYSQL_ROOT_PASSWORD": true,
	"DB_PASSWORD":         true,
	"REDIS_PASSWORD":      true,
	"TOKEN_SECRET":        true,
	"ROCKETMQ_ACCESS_KEY": true,
	"ROCKETMQ_SECRET_KEY": true,
}

var dockerMirrorPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]*(:[0-9]{1,5})?(/[a-z0-9._/-]+)?$`)
var rocketMQNameServerPattern = regexp.MustCompile(`^[A-Za-z0-9._-]+:([0-9]{1,5})$`)

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
	if err := c.validateDeploymentDataRoot(values); err != nil {
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
	c.nginxSnapshot(mode, values, safe)
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

// validateDeploymentDataRoot 保证业务配置、升级器产物路径和白名单配置目录属于
// 同一个数据根。Docker Compose 会按 DATA_ROOT 挂载宿主机目录，若只提示不阻断，
// 升级器可能备份一套目录却重启另一套容器。
func (c *Config) validateDeploymentDataRoot(values map[string]string) error {
	configured := strings.TrimSpace(values["DATA_ROOT"])
	configured = trimTrailingPathSeparators(configured)
	if !filepath.IsAbs(configured) || filepath.Clean(configured) != configured {
		return fmt.Errorf("DATA_ROOT必须是绝对路径")
	}
	allowedConfigured := trimTrailingPathSeparators(strings.TrimSpace(c.Deployment.AllowedConfigRoot))
	if !filepath.IsAbs(allowedConfigured) || filepath.Clean(allowedConfigured) != allowedConfigured {
		return fmt.Errorf("升级器配置目录非法")
	}
	allowedRoot, err := filepath.Abs(allowedConfigured)
	if err != nil {
		return fmt.Errorf("升级器配置目录非法")
	}
	expectedRoot := filepath.Dir(filepath.Clean(allowedRoot))
	if isFilesystemRoot(expectedRoot) {
		return fmt.Errorf("升级器受管目录不能位于文件系统根")
	}
	configuredRoot, err := filepath.Abs(configured)
	if err != nil {
		return fmt.Errorf("DATA_ROOT路径非法: %w", err)
	}
	if filepath.Clean(configuredRoot) != expectedRoot {
		return fmt.Errorf("DATA_ROOT与升级器受管目录不一致")
	}
	if err := rejectSymlinkComponents(expectedRoot); err != nil {
		return fmt.Errorf("DATA_ROOT路径非法: %w", err)
	}
	return nil
}

func trimTrailingPathSeparators(path string) string {
	minimumLength := len(filepath.VolumeName(path)) + 1
	for len(path) > minimumLength && os.IsPathSeparator(path[len(path)-1]) {
		path = path[:len(path)-1]
	}
	return path
}

func isFilesystemRoot(path string) bool {
	clean := filepath.Clean(path)
	volumeRoot := filepath.Clean(filepath.VolumeName(clean) + string(os.PathSeparator))
	return clean == string(os.PathSeparator) || clean == volumeRoot
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
	if err := ValidateNginxValues(state.Mode, state.Values); err != nil {
		return nil, nil, err
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

// BuildDeploymentDiagnosticState 只为单项诊断合并显式白名单字段，不写配置文件，
// 也不触发其他尚未完成配置项的跨项校验。
func (c *Config) BuildDeploymentDiagnosticState(targetPath string, changes map[string]string,
	allowedKeys map[string]bool) (*DeploymentState, error) {
	state, err := c.ReadDeploymentState()
	if err != nil {
		return nil, err
	}
	resolved, err := c.ResolveDeploymentConfigPath(targetPath)
	if err != nil {
		return nil, err
	}
	values := make(map[string]string, len(state.Values))
	for key, value := range state.Values {
		values[key] = value
	}
	for key, value := range changes {
		if !allowedKeys[key] || !isAllowedDeploymentKey(state.Mode, key) {
			return nil, fmt.Errorf("诊断不支持配置项: %s", key)
		}
		if strings.ContainsAny(value, "\r\n\x00") {
			return nil, fmt.Errorf("配置项 %s 包含非法字符", key)
		}
		if secretDeploymentKeys[key] && strings.ContainsAny(value, " #\"'$\\") {
			return nil, fmt.Errorf("配置项 %s 包含不支持的密钥字符", key)
		}
		values[key] = value
	}
	state.Values = values
	state.ConfigPath = resolved
	return state, nil
}

// ValidateDeploymentDiagnostic 仅校验目标诊断真正依赖的字段，避免 DNS、数据库等
// 单项检测被尚未完成的其他配置阻断，同时在网络探测前限制地址和路径格式。
func ValidateDeploymentDiagnostic(target string, state *DeploymentState) error {
	values := state.Values
	switch target {
	case "dns":
		return validateHTTPSDomains(values)
	case "certificate":
		if err := validateHTTPSDomains(values); err != nil {
			return err
		}
		certificatePath := strings.TrimSpace(values["HTTPS_CERT_PATH"])
		privateKeyPath := strings.TrimSpace(values["HTTPS_KEY_PATH"])
		if certificatePath == "" && privateKeyPath == "" {
			return nil
		}
		if certificatePath == "" || privateKeyPath == "" {
			return fmt.Errorf("HTTPS证书与私钥路径必须同时配置")
		}
		dataRoot := filepath.Clean(strings.TrimSpace(values["DATA_ROOT"]))
		if !filepath.IsAbs(dataRoot) {
			return fmt.Errorf("DATA_ROOT必须是绝对路径")
		}
		sslRoot := filepath.Join(dataRoot, "config", "ssl")
		for _, key := range []string{"HTTPS_CERT_PATH", "HTTPS_KEY_PATH"} {
			if err := validateTLSFile(values[key], sslRoot); err != nil {
				return fmt.Errorf("%s校验失败: %w", key, err)
			}
		}
		return nil
	case "https":
		enabled := values["HTTPS_ENABLED"] == "true"
		if state.Mode == "docker" {
			profiles, err := parseComposeProfiles(values["COMPOSE_PROFILES"])
			if err != nil {
				return err
			}
			enabled = profiles["https"]
		}
		if !enabled {
			return nil
		}
		if !validPort(valueOr(values, "HTTPS_PORT", "443")) {
			return fmt.Errorf("HTTPS端口格式错误")
		}
		return validateHTTPSValues(values)
	case "mysql":
		for _, key := range []string{"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD"} {
			if strings.TrimSpace(values[key]) == "" {
				return fmt.Errorf("配置项 %s 不能为空", key)
			}
		}
		if !validNetworkHost(values["DB_HOST"]) || !validPort(values["DB_PORT"]) {
			return fmt.Errorf("MySQL地址或端口格式错误")
		}
		if !alphaNumericUnderscore(strings.TrimSpace(values["DB_NAME"])) {
			return fmt.Errorf("DB_NAME仅允许字母数字和下划线")
		}
		if state.Mode == "docker" {
			profiles, err := parseComposeProfiles(values["COMPOSE_PROFILES"])
			if err != nil {
				return err
			}
			if profiles["mysql"] && (values["DB_HOST"] != "mysql" || values["DB_PORT"] != "3306") {
				return fmt.Errorf("内置MySQL地址必须为mysql:3306")
			}
		}
		return nil
	case "redis":
		if !validNetworkHost(values["REDIS_HOST"]) || !validPort(values["REDIS_PORT"]) {
			return fmt.Errorf("Redis地址或端口格式错误")
		}
		if database := strings.TrimSpace(values["REDIS_DATABASE"]); database != "" {
			index, err := strconv.Atoi(database)
			if err != nil || index < 0 {
				return fmt.Errorf("REDIS_DATABASE 必须是非负整数")
			}
		}
		return nil
	case "rocketmq":
		if values["ROCKETMQ_ENABLED"] != "true" {
			return nil
		}
		return validateRocketMQNameServers(values["ROCKETMQ_NAMESERVER"])
	default:
		return fmt.Errorf("不支持的诊断项")
	}
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
	required := []string{"DATA_ROOT", "HTTP_PORT", "ADMIN_PORT", "BACKEND_PORT", "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "TOKEN_SECRET", "REDIS_HOST", "REDIS_PORT"}
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
	dependencyMode := valueOr(values, "DEPENDENCY_INSTALL_MODE", "auto")
	if dependencyMode != "auto" && dependencyMode != "manual" {
		return fmt.Errorf("DEPENDENCY_INSTALL_MODE 只支持 auto 或 manual")
	}
	dependencyRegion := valueOr(values, "DEPENDENCY_REGION", "auto")
	if dependencyRegion != "auto" && dependencyRegion != "cn" && dependencyRegion != "global" {
		return fmt.Errorf("DEPENDENCY_REGION 只支持 auto、cn 或 global")
	}
	if err := validateDockerMirrors(values["DOCKER_MIRRORS"]); err != nil {
		return err
	}
	if value := strings.TrimSpace(values["DOWNLOAD_TIMEOUT_SECONDS"]); value != "" {
		timeoutSeconds, err := strconv.Atoi(value)
		if err != nil || timeoutSeconds < 0 {
			return fmt.Errorf("DOWNLOAD_TIMEOUT_SECONDS 必须为非负整数；0表示不限总下载时长")
		}
	}
	flushDiskType := valueOr(values, "ROCKETMQ_FLUSH_DISK_TYPE", "ASYNC_FLUSH")
	if flushDiskType != "ASYNC_FLUSH" && flushDiskType != "SYNC_FLUSH" {
		return fmt.Errorf("ROCKETMQ_FLUSH_DISK_TYPE 只支持 ASYNC_FLUSH 或 SYNC_FLUSH")
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
	if values["ROCKETMQ_ENABLED"] == "true" {
		if err := validateRocketMQNameServers(values["ROCKETMQ_NAMESERVER"]); err != nil {
			return err
		}
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
		mqEnabled := values["ROCKETMQ_ENABLED"] == "true"
		if profiles["mq"] && !mqEnabled {
			return fmt.Errorf("mq组件启用时必须开启RocketMQ")
		}
		if profiles["mq"] && strings.TrimSpace(values["ROCKETMQ_NAMESERVER"]) != "rocketmq-nameserver:9876" {
			return fmt.Errorf("内置RocketMQ地址必须使用rocketmq-nameserver:9876")
		}
		if mqEnabled && !profiles["mq"] && strings.Contains(values["ROCKETMQ_NAMESERVER"], "rocketmq-nameserver:") {
			return fmt.Errorf("外部RocketMQ必须填写真实NameServer地址")
		}
		if mqEnabled && !profiles["mq"] && rocketMQNameServersContainLoopback(values["ROCKETMQ_NAMESERVER"]) {
			return fmt.Errorf("Docker外部RocketMQ不能使用127.0.0.1或localhost，宿主机MQ请使用host.docker.internal")
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

func validateRocketMQNameServers(value string) error {
	entries := strings.FieldsFunc(value, func(r rune) bool { return r == ';' || r == ',' })
	if len(entries) == 0 {
		return fmt.Errorf("RocketMQ地址不能为空")
	}
	for _, entry := range entries {
		entry = strings.TrimSpace(entry)
		matches := rocketMQNameServerPattern.FindStringSubmatch(entry)
		if len(matches) != 2 {
			return fmt.Errorf("RocketMQ地址必须使用host:port")
		}
		port, err := strconv.Atoi(matches[1])
		if err != nil || port < 1 || port > 65535 {
			return fmt.Errorf("RocketMQ端口范围错误")
		}
	}
	return nil
}

func rocketMQNameServersContainLoopback(value string) bool {
	entries := strings.FieldsFunc(value, func(r rune) bool { return r == ';' || r == ',' })
	for _, entry := range entries {
		host, _, found := strings.Cut(strings.TrimSpace(entry), ":")
		if !found {
			continue
		}
		if strings.EqualFold(host, "localhost") || host == "127.0.0.1" {
			return true
		}
	}
	return false
}

// validateDockerMirrors 限制 Registry 前缀为无凭据、无查询参数的镜像地址，防止
// 配置内容在 Shell/Docker 命令边界产生歧义。空值表示使用安装器内置候选列表。
func validateDockerMirrors(value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	if len(value) > 1024 {
		return fmt.Errorf("DOCKER_MIRRORS 内容过长")
	}
	mirrors := strings.Split(value, ",")
	if len(mirrors) > 8 {
		return fmt.Errorf("DOCKER_MIRRORS 最多配置8个地址")
	}
	for _, raw := range mirrors {
		mirror := strings.ToLower(strings.TrimSpace(raw))
		mirror = strings.TrimPrefix(mirror, "https://")
		mirror = strings.TrimPrefix(mirror, "http://")
		mirror = strings.TrimSuffix(mirror, "/")
		if !dockerMirrorPattern.MatchString(mirror) || strings.ContainsAny(mirror, "@?#\\") {
			return fmt.Errorf("DOCKER_MIRRORS 地址格式错误")
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
	if err := validateHTTPSDomains(values); err != nil {
		return err
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

func validateHTTPSDomains(values map[string]string) error {
	publicDomain := strings.TrimSpace(values["HTTPS_PUBLIC_DOMAIN"])
	adminDomain := strings.TrimSpace(values["HTTPS_ADMIN_DOMAIN"])
	if !validServerName(publicDomain) || !validServerName(adminDomain) {
		return fmt.Errorf("HTTPS域名格式错误")
	}
	if strings.EqualFold(publicDomain, adminDomain) {
		return fmt.Errorf("HTTPS用户端和管理端域名不能相同")
	}
	return nil
}

func validNetworkHost(value string) bool {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 253 || strings.ContainsAny(value, " /\\\t\r\n@?#") {
		return false
	}
	return net.ParseIP(value) != nil || validServerName(value)
}

func validPort(value string) bool {
	port, err := strconv.Atoi(strings.TrimSpace(value))
	return err == nil && port >= 1 && port <= 65535
}

func validServerName(value string) bool {
	value = strings.TrimSpace(value)
	if value == "" || len(value) > 253 || strings.ContainsAny(value, " /\\:\t\r\n") {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, char := range label {
			if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
				(char >= '0' && char <= '9') || char == '-' {
				continue
			}
			return false
		}
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
