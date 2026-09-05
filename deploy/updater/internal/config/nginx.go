package config

import (
	"crypto/sha256"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var NginxKeys = []string{"NGINX_BACKEND_ORIGIN", "NGINX_MAX_BODY_MB", "NGINX_READ_TIMEOUT_SECONDS", "NGINX_CONNECT_TIMEOUT_SECONDS", "NGINX_EXTRA_DIRECTIVES"}
var nginxOriginPattern = regexp.MustCompile(`^https?://([a-zA-Z0-9][a-zA-Z0-9.-]*|\[[0-9a-fA-F:]+\])(:[0-9]+)?$`)
var nginxDirectivePattern = regexp.MustCompile(`^(gzip (on|off)|gzip_min_length [1-9][0-9]*|(keepalive_timeout|client_body_timeout|send_timeout) [1-9][0-9]*s)$`)

// NginxValues supplies installation-specific defaults without persisting them.
func NginxValues(mode string, values map[string]string) map[string]string {
	origin := "http://127.0.0.1:" + valueOr(values, "BACKEND_PORT", "8080")
	if mode == "docker" {
		origin = "http://aid-server:8080"
	}
	result := map[string]string{"NGINX_BACKEND_ORIGIN": origin, "NGINX_MAX_BODY_MB": "1024", "NGINX_READ_TIMEOUT_SECONDS": "300", "NGINX_CONNECT_TIMEOUT_SECONDS": "10", "NGINX_EXTRA_DIRECTIVES": ""}
	for _, key := range NginxKeys {
		if value, ok := values[key]; ok && (value != "" || key == "NGINX_EXTRA_DIRECTIVES") {
			result[key] = value
		}
	}
	return result
}

func ValidateNginxValues(mode string, raw map[string]string) error {
	values := NginxValues(mode, raw)
	origin := values["NGINX_BACKEND_ORIGIN"]
	parsed, err := url.Parse(origin)
	if err != nil || len(origin) > 255 || !nginxOriginPattern.MatchString(origin) || parsed.Hostname() == "" {
		return fmt.Errorf("后端地址必须为HTTP(S)协议、主机及可选端口，不得包含路径或凭证")
	}
	if port := parsed.Port(); port != "" {
		n, e := strconv.Atoi(port)
		if e != nil || n < 1 || n > 65535 {
			return fmt.Errorf("后端端口无效")
		}
	}
	limits := map[string]int{"NGINX_MAX_BODY_MB": 10240, "NGINX_READ_TIMEOUT_SECONDS": 3600, "NGINX_CONNECT_TIMEOUT_SECONDS": 120}
	for key, limit := range limits {
		n, e := strconv.Atoi(values[key])
		if e != nil || n < 1 || n > limit || strconv.Itoa(n) != values[key] {
			return fmt.Errorf("Nginx参数%s超出范围", key)
		}
	}
	extra := values["NGINX_EXTRA_DIRECTIVES"]
	if len(extra) > 2048 || strings.ContainsAny(extra, "\r\n\x00") {
		return fmt.Errorf("扩展指令过长或包含非法字符")
	}
	seen := map[string]bool{}
	parts := strings.Split(extra, ";")
	for i, part := range parts {
		line := strings.TrimSpace(part)
		if line == "" {
			continue
		}
		if i == len(parts)-1 || !nginxDirectivePattern.MatchString(line) {
			return fmt.Errorf("仅允许gzip、gzip_min_length、keepalive_timeout、client_body_timeout、send_timeout指令")
		}
		key := strings.Fields(line)[0]
		if seen[key] {
			return fmt.Errorf("扩展指令重复")
		}
		seen[key] = true
	}
	return nil
}

// NginxSnapshot advertises additive capability without breaking older updaters.
func (c *Config) nginxSnapshot(mode string, values, safe map[string]string) {
	effective := NginxValues(mode, values)
	digest := sha256.New()
	for _, key := range NginxKeys {
		safe[key] = effective[key]
		fmt.Fprintf(digest, "%s=%s\n", key, effective[key])
	}
	available := SupportsManagedNginx(c.Deployment.ManagerScript)
	for _, kind := range []string{"public", "admin"} {
		path := filepath.Join(c.Deployment.AllowedConfigRoot, "nginx-managed", kind+".conf")
		raw, err := ReadManagedNginx(path)
		if err != nil {
			available = false
			continue
		}
		digest.Write(raw)
		safe["NGINX_"+strings.ToUpper(kind)+"_PREVIEW"] = string(raw)
	}
	script := filepath.Join(filepath.Dir(c.Deployment.ManagerScript), "nginx", "render.sh")
	if info, err := os.Lstat(script); err != nil || !info.Mode().IsRegular() {
		available = false
	}
	safe["NGINX_MANAGEMENT_AVAILABLE"] = strconv.FormatBool(available)
	safe["NGINX_REVISION"] = fmt.Sprintf("%x", digest.Sum(nil))
}

// Capability belongs to the installed manager, not leftover templates from a newer release.
func SupportsManagedNginx(manager string) bool {
	if err := rejectSymlinkComponents(manager); err != nil {
		return false
	}
	info, err := os.Lstat(manager)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 2*1024*1024 {
		return false
	}
	raw, err := os.ReadFile(manager)
	return err == nil && strings.Contains(string(raw), "# AID_NGINX_MANAGEMENT=1\n")
}

func ReadManagedNginx(path string) ([]byte, error) {
	if err := rejectSymlinkComponents(path); err != nil {
		return nil, err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > 16384 {
		return nil, fmt.Errorf("Nginx受管文件无效")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if !strings.HasPrefix(string(raw), "# AID_MANAGED_NGINX_INCLUDE=1\n") {
		return nil, fmt.Errorf("拒绝修改非受管Nginx文件")
	}
	return raw, nil
}
