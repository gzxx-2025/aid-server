package task

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"

	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
	"aid-updater/internal/health"
)

const diagnosticTimeout = 6 * time.Second

var allowedConfigTestTargets = map[string]bool{
	"config": true, "dns": true, "certificate": true, "https": true,
	"mysql": true, "redis": true, "rocketmq": true,
}

var configTestTargetKeys = map[string]map[string]bool{
	"dns": {
		"HTTPS_PUBLIC_DOMAIN": true, "HTTPS_ADMIN_DOMAIN": true,
	},
	"certificate": {
		"DATA_ROOT": true, "HTTPS_PUBLIC_DOMAIN": true, "HTTPS_ADMIN_DOMAIN": true,
		"HTTPS_CERT_PATH": true, "HTTPS_KEY_PATH": true,
	},
	"https": {
		"DATA_ROOT": true, "HTTP_PORT": true, "ADMIN_PORT": true, "COMPOSE_PROFILES": true,
		"HTTPS_ENABLED": true, "HTTPS_PORT": true, "HTTPS_PUBLIC_DOMAIN": true,
		"HTTPS_ADMIN_DOMAIN": true, "HTTPS_CERT_PATH": true, "HTTPS_KEY_PATH": true,
	},
	"mysql": {
		"COMPOSE_PROFILES": true, "DB_HOST": true, "DB_PORT": true, "DB_NAME": true,
		"DB_USERNAME": true, "DB_PASSWORD": true,
	},
	"redis": {
		"COMPOSE_PROFILES": true, "REDIS_HOST": true, "REDIS_PORT": true,
		"REDIS_USERNAME": true, "REDIS_PASSWORD": true, "REDIS_DATABASE": true,
	},
	"rocketmq": {
		"COMPOSE_PROFILES": true, "ROCKETMQ_ENABLED": true, "ROCKETMQ_NAMESERVER": true,
		"ROCKETMQ_ACCESS_KEY": true, "ROCKETMQ_SECRET_KEY": true,
	},
}

func (r *Runner) runConfigTest(t *Task) error {
	targets := t.TestTargets
	if len(targets) == 0 {
		targets = []string{"config", "dns", "certificate", "https", "mysql", "redis", "rocketmq"}
	}
	checks := make(map[string]health.CheckResult, len(targets))
	for _, target := range targets {
		target = strings.ToLower(strings.TrimSpace(target))
		if !allowedConfigTestTargets[target] {
			return fmt.Errorf("不支持的诊断项")
		}
		if target == "config" {
			raw, state, err := r.cfg.BuildDeploymentConfig(t.ConfigPath, t.ConfigValues)
			if err != nil {
				checks[target] = health.CheckResult{Status: "FAIL", Message: trimMessage(err.Error()), Suggestion: "请检查必填项与组件组合"}
				continue
			}
			if err := r.validateRenderedConfiguration(state, raw); err != nil {
				checks[target] = health.CheckResult{Status: "FAIL", Message: "配置结构或部署编排校验失败", Suggestion: "请检查必填项、组件组合和证书文件"}
			} else {
				checks[target] = health.CheckResult{Status: "PASS", Message: "配置结构与部署编排校验通过"}
			}
			continue
		}
		state, err := r.buildDiagnosticState(t, target)
		if err != nil {
			checks[target] = health.CheckResult{Status: "FAIL", Message: trimMessage(err.Error()), Suggestion: "请检查该诊断项的配置"}
			continue
		}
		if err := config.ValidateDeploymentDiagnostic(target, state); err != nil {
			checks[target] = health.CheckResult{Status: "FAIL", Message: trimMessage(err.Error()), Suggestion: "请修正该诊断项后重试"}
			continue
		}
		switch target {
		case "dns":
			checks[target] = testDNS(state.Values)
		case "certificate":
			checks[target] = testCertificate(state.Values)
		case "https":
			checks[target] = testHTTPS(state)
		case "mysql":
			checks[target] = testMySQL(state)
		case "redis":
			checks[target] = testRedis(state.Values)
		case "rocketmq":
			checks[target] = testRocketMQ(state.Values)
		}
	}
	r.reporter.SetTaskChecks(t.TaskID, t.Action, checks)
	return nil
}

func (r *Runner) buildDiagnosticState(t *Task, target string) (*config.DeploymentState, error) {
	allowedKeys, ok := configTestTargetKeys[target]
	if !ok {
		return nil, fmt.Errorf("不支持的诊断项")
	}
	changes := make(map[string]string)
	for key, value := range t.ConfigValues {
		if allowedKeys[key] {
			changes[key] = value
		}
	}
	return r.cfg.BuildDeploymentDiagnosticState(t.ConfigPath, changes, allowedKeys)
}

func checkResult(err error, success, suggestion string) health.CheckResult {
	if err == nil {
		return health.CheckResult{Status: "PASS", Message: success}
	}
	return health.CheckResult{Status: "FAIL", Message: trimMessage(err.Error()), Suggestion: suggestion}
}

func skippedResult(message string) health.CheckResult {
	return health.CheckResult{Status: "SKIPPED", Message: message}
}

func testDNS(values map[string]string) health.CheckResult {
	domains := []string{strings.TrimSpace(values["HTTPS_PUBLIC_DOMAIN"]), strings.TrimSpace(values["HTTPS_ADMIN_DOMAIN"])}
	if domains[0] == "" && domains[1] == "" {
		return skippedResult("未配置 HTTPS 域名")
	}
	resolved := make([]string, 0, len(domains))
	for index, domain := range domains {
		if domain == "" {
			return health.CheckResult{Status: "FAIL", Message: "用户域名和管理域名需要同时配置", Suggestion: "请补齐两个域名后重试"}
		}
		addresses, err := net.LookupHost(domain)
		if err != nil || len(addresses) == 0 {
			return health.CheckResult{Status: "FAIL", Message: fmt.Sprintf("域名 %s 暂无有效解析", domain), Suggestion: "请检查 DNS A/AAAA 记录并等待解析生效"}
		}
		label := "用户域名"
		if index == 1 {
			label = "管理域名"
		}
		resolved = append(resolved, label+"="+uniqueIPAddresses(addresses))
	}
	return health.CheckResult{Status: "PASS", Message: "DNS 解析正常：" + strings.Join(resolved, "；")}
}

func uniqueIPAddresses(addresses []string) string {
	seen := make(map[string]bool)
	resolved := make([]string, 0, len(addresses))
	for _, address := range addresses {
		ip := net.ParseIP(strings.TrimSpace(address))
		if ip == nil {
			continue
		}
		value := ip.String()
		if !seen[value] {
			seen[value] = true
			resolved = append(resolved, value)
		}
	}
	return strings.Join(resolved, ", ")
}

func testCertificate(values map[string]string) health.CheckResult {
	certPath := strings.TrimSpace(values["HTTPS_CERT_PATH"])
	keyPath := strings.TrimSpace(values["HTTPS_KEY_PATH"])
	if certPath == "" && keyPath == "" {
		return skippedResult("尚未上传 HTTPS 证书")
	}
	certPEM, err := readLimitedRegularFile(certPath)
	if err != nil {
		return checkResult(err, "", "请重新上传完整证书链")
	}
	keyPEM, err := readLimitedRegularFile(keyPath)
	if err != nil {
		return checkResult(err, "", "请重新上传匹配的私钥")
	}
	certificate, err := validateCertificatePair(certPEM, keyPEM,
		values["HTTPS_PUBLIC_DOMAIN"], values["HTTPS_ADMIN_DOMAIN"])
	if err != nil {
		return checkResult(err, "", "请上传覆盖两个域名且公私钥匹配的 PEM 文件")
	}
	return health.CheckResult{Status: "PASS", Message: "证书有效，覆盖已配置域名，有效期至 " + certificate.NotAfter.Format("2006-01-02")}
}

func testHTTPS(state *config.DeploymentState) health.CheckResult {
	enabled := state.Values["HTTPS_ENABLED"] == "true"
	if state.Mode == "docker" {
		enabled = deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "https")
	}
	if !enabled {
		return skippedResult("HTTPS 未启用，已跳过服务可达性检测")
	}
	port := strings.TrimSpace(state.Values["HTTPS_PORT"])
	if port == "" {
		port = "443"
	}
	for _, domain := range []string{state.Values["HTTPS_PUBLIC_DOMAIN"], state.Values["HTTPS_ADMIN_DOMAIN"]} {
		// 从服务器本机入口握手，避免部分云网络不支持公网 IP NAT hairpin 造成误报；
		// ServerName 仍使用真实域名，因此证书链和主机名验证不会被绕过。
		checkHost := "127.0.0.1"
		checkPort := port
		if state.Mode == "docker" {
			checkHost = "nginx-https"
			checkPort = "443"
		}
		address := net.JoinHostPort(checkHost, checkPort)
		dialer := &net.Dialer{Timeout: diagnosticTimeout}
		connection, err := tls.DialWithDialer(dialer, "tcp", address, &tls.Config{ServerName: strings.TrimSpace(domain), MinVersion: tls.VersionTLS12})
		if err != nil {
			return health.CheckResult{Status: "FAIL", Message: "HTTPS 服务暂不可达", Suggestion: "请确认 DNS、证书、443 防火墙和服务重启状态"}
		}
		_ = connection.Close()
	}
	return health.CheckResult{Status: "PASS", Message: "用户端与管理端 HTTPS 均可安全访问"}
}

func testMySQL(state *config.DeploymentState) health.CheckResult {
	port, err := strconv.Atoi(state.Values["DB_PORT"])
	if err != nil {
		return checkResult(err, "", "请检查数据库端口")
	}
	database := config.Database{Enabled: true, Host: state.Values["DB_HOST"], Port: port,
		Name: state.Values["DB_NAME"], User: state.Values["DB_USERNAME"], Password: state.Values["DB_PASSWORD"]}
	if state.Mode == "docker" && deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "mysql") {
		database.ExecContainer = "aid-mysql"
	} else if state.Mode == "docker" {
		database.ClientImage = "mysql:5.7"
		database.DockerNetwork = "host"
	}
	version, err := dbexec.Query(database, "SELECT VERSION()")
	if err != nil {
		return checkResult(fmt.Errorf("MySQL 连接或认证失败"), "", "请检查地址、端口、账号、密码及网络白名单")
	}
	version = strings.TrimSpace(version)
	if !strings.HasPrefix(version, "5.7.") {
		return health.CheckResult{Status: "FAIL", Message: "MySQL 版本不是 5.7", Suggestion: "请使用 MySQL 5.7 后重试"}
	}
	return health.CheckResult{Status: "PASS", Message: "MySQL 5.7 连接与认证正常"}
}

func testRedis(values map[string]string) health.CheckResult {
	address := net.JoinHostPort(strings.TrimSpace(values["REDIS_HOST"]), strings.TrimSpace(values["REDIS_PORT"]))
	connection, err := net.DialTimeout("tcp", address, diagnosticTimeout)
	if err != nil {
		return health.CheckResult{Status: "FAIL", Message: "Redis 网络不可达", Suggestion: "请检查地址、端口及网络策略"}
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(diagnosticTimeout))
	reader := bufio.NewReader(connection)
	username := values["REDIS_USERNAME"]
	password := values["REDIS_PASSWORD"]
	if password != "" {
		arguments := []string{"AUTH"}
		if username != "" {
			arguments = append(arguments, username)
		}
		arguments = append(arguments, password)
		if err := writeRedisCommand(connection, arguments...); err != nil {
			return checkResult(err, "", "请检查 Redis 认证配置")
		}
		response, err := reader.ReadString('\n')
		if err != nil || !strings.HasPrefix(response, "+OK") {
			return health.CheckResult{Status: "FAIL", Message: "Redis 认证失败", Suggestion: "请检查 ACL 用户名和密码"}
		}
	}
	database := strings.TrimSpace(values["REDIS_DATABASE"])
	if database == "" {
		database = "0"
	}
	if err := selectRedisDatabase(connection, reader, database); err != nil {
		return health.CheckResult{Status: "FAIL", Message: "Redis 数据库选择失败", Suggestion: "请检查数据库索引是否存在，集群模式通常仅支持 0 库"}
	}
	if err := writeRedisCommand(connection, "PING"); err != nil {
		return checkResult(err, "", "请检查 Redis 服务状态")
	}
	response, err := reader.ReadString('\n')
	if err != nil || !strings.HasPrefix(response, "+PONG") {
		return health.CheckResult{Status: "FAIL", Message: "Redis PING 未通过", Suggestion: "请确认 Redis 可用且未被保护模式阻断"}
	}
	return health.CheckResult{Status: "PASS", Message: "Redis 连接、认证与 PING 正常"}
}

func selectRedisDatabase(connection net.Conn, reader *bufio.Reader, database string) error {
	if err := writeRedisCommand(connection, "SELECT", database); err != nil {
		return err
	}
	response, err := reader.ReadString('\n')
	if err != nil || !strings.HasPrefix(response, "+OK") {
		return fmt.Errorf("Redis SELECT 未通过")
	}
	return nil
}

func writeRedisCommand(connection net.Conn, arguments ...string) error {
	var builder strings.Builder
	fmt.Fprintf(&builder, "*%d\r\n", len(arguments))
	for _, argument := range arguments {
		fmt.Fprintf(&builder, "$%d\r\n%s\r\n", len(argument), argument)
	}
	_, err := connection.Write([]byte(builder.String()))
	return err
}

func testRocketMQ(values map[string]string) health.CheckResult {
	if values["ROCKETMQ_ENABLED"] != "true" {
		return skippedResult("RocketMQ 未启用，已跳过")
	}
	entries := strings.FieldsFunc(values["ROCKETMQ_NAMESERVER"], func(r rune) bool { return r == ';' || r == ',' })
	for _, entry := range entries {
		connection, err := net.DialTimeout("tcp", strings.TrimSpace(entry), diagnosticTimeout)
		if err != nil {
			return health.CheckResult{Status: "FAIL", Message: "RocketMQ NameServer 不可达", Suggestion: "请检查 NameServer 地址、端口和容器网络"}
		}
		_ = connection.Close()
	}
	return health.CheckResult{Status: "PASS", Message: "RocketMQ NameServer 网络连通正常"}
}
