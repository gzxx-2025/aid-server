// SQL 执行记录（schema history）：让升级器自动区分"哪些脚本该执行、哪些已执行过"。
// 机制与 Flyway 一致——数据库内维护一张执行记录表，按脚本文件名判重：
//   - 已成功执行过的脚本自动跳过（重复升级、跨版本包携带旧脚本都不会重放）
//   - 上次失败的脚本允许重试
//   - 内容变化（校验和不一致）但已执行过的脚本告警跳过，不重放
package dbexec

import (
	"crypto/sha256"
	"fmt"
	"log"
	"os"
	"strings"

	"aid-updater/internal/config"
)

// 执行记录表：升级器自动创建，业务代码不感知。
const historyTableDDL = `CREATE TABLE IF NOT EXISTS aid_schema_history (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  script_name VARCHAR(255) NOT NULL COMMENT '脚本文件名',
  checksum CHAR(64) NOT NULL COMMENT '脚本内容SHA256',
  status VARCHAR(16) NOT NULL COMMENT '执行状态 SUCCESS/FAILED',
  error_message VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  executed_at DATETIME NOT NULL COMMENT '执行时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_script_name (script_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库升级脚本执行记录（升级器维护）'`

// scriptRecord 描述一条执行记录。
type scriptRecord struct {
	Checksum string
	Status   string
}

// runQuery 执行一条 SQL 并返回原始输出（批处理模式，制表符分隔）。
func runQuery(db config.Database, query string) (string, error) {
	cmd := buildDBCommand(db, "mysql",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--default-character-set=utf8mb4",
		"--batch", "--skip-column-names",
		"-e", query,
		db.Name,
	)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	return string(output), nil
}

// ensureHistoryTable 确保执行记录表存在（幂等）。
func ensureHistoryTable(db config.Database) error {
	if _, err := runQuery(db, historyTableDDL); err != nil {
		return fmt.Errorf("创建脚本执行记录表失败: %w", err)
	}
	return nil
}

// loadHistory 读取全部执行记录，key 为脚本文件名。
func loadHistory(db config.Database) (map[string]scriptRecord, error) {
	output, err := runQuery(db, "SELECT script_name, checksum, status FROM aid_schema_history")
	if err != nil {
		return nil, fmt.Errorf("读取脚本执行记录失败: %w", err)
	}
	records := make(map[string]scriptRecord)
	for _, line := range strings.Split(strings.TrimSpace(output), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		fields := strings.Split(line, "\t")
		if len(fields) < 3 {
			continue
		}
		records[fields[0]] = scriptRecord{Checksum: fields[1], Status: fields[2]}
	}
	return records, nil
}

// markScript 写入/覆盖一条执行记录。
func markScript(db config.Database, name, checksum, status, errMessage string) error {
	// 按字符截断，避免切坏多字节 UTF-8 导致写库失败
	if runes := []rune(errMessage); len(runes) > 400 {
		errMessage = string(runes[:400])
	}
	query := fmt.Sprintf(
		"INSERT INTO aid_schema_history (script_name, checksum, status, error_message, executed_at)"+
			" VALUES ('%s', '%s', '%s', '%s', NOW())"+
			" ON DUPLICATE KEY UPDATE checksum=VALUES(checksum), status=VALUES(status),"+
			" error_message=VALUES(error_message), executed_at=VALUES(executed_at)",
		escapeSQL(name), checksum, status, escapeSQL(errMessage))
	if _, err := runQuery(db, query); err != nil {
		return fmt.Errorf("写入脚本执行记录失败: %w", err)
	}
	return nil
}

// fileChecksum 计算脚本内容 SHA256（十六进制小写）。
func fileChecksum(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%x", sha256.Sum256(raw)), nil
}

// escapeSQL 对拼入 SQL 字面量的值做最小转义（值均来自升级器自身，非用户输入）。
func escapeSQL(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, `'`, `''`)
	return value
}

// logSkip 统一的跳过日志。
func logSkip(name, reason string) {
	log.Printf("跳过SQL脚本 %s: %s", name, reason)
}
