// Package dbexec 通过 mysql/mysqldump 命令行工具执行 SQL 与数据库备份。
package dbexec

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"aid-updater/internal/config"
)

// buildDBCommand 组装 mysql/mysqldump 命令：execContainer 非空时经 `docker exec`
// 在数据库容器内执行（Docker 部署无需本地 MySQL 客户端）。密码始终走环境变量
// 链路（本地直调进程环境；容器模式 docker exec 的 -e 只传变量名，由 CLI 从自身
// 环境取值转发），不出现在任何命令行参数中。
func buildDBCommand(db config.Database, tool string, toolArgs ...string) *exec.Cmd {
	var cmd *exec.Cmd
	if strings.TrimSpace(db.ExecContainer) != "" {
		args := []string{"exec", "-i", "-e", "MYSQL_PWD", strings.TrimSpace(db.ExecContainer), tool}
		args = append(args, toolArgs...)
		cmd = exec.Command("docker", args...)
	} else {
		cmd = exec.Command(tool, toolArgs...)
	}
	cmd.Env = append(os.Environ(), "MYSQL_PWD="+db.Password)
	return cmd
}

// ExecuteScript 执行单个 SQL 文件。
func ExecuteScript(db config.Database, scriptPath string) error {
	f, err := os.Open(scriptPath)
	if err != nil {
		return fmt.Errorf("打开SQL脚本失败: %w", err)
	}
	defer f.Close()

	cmd := buildDBCommand(db, "mysql",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--default-character-set=utf8mb4",
		db.Name,
	)
	cmd.Stdin = f
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("执行SQL脚本 %s 失败: %v, 输出: %s",
			filepath.Base(scriptPath), err, strings.TrimSpace(string(output)))
	}
	return nil
}

// ExecuteDir 按文件名升序执行目录内的 .sql 脚本，返回本次实际执行数量。
// 通过 aid_schema_history 执行记录表自动判重（Flyway 模式）：
//   - 同名脚本已成功执行过 → 跳过（重复升级 / 升级包携带旧版本脚本都不会重放）
//   - 同名但上次失败 → 允许重试
//   - 同名已成功但内容有变化 → 告警跳过（已生效的历史脚本不重放，修正应放入新脚本）
func ExecuteDir(db config.Database, dir string) (int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, fmt.Errorf("读取SQL目录失败: %w", err)
	}
	var scripts []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(strings.ToLower(entry.Name()), ".sql") {
			scripts = append(scripts, entry.Name())
		}
	}
	if len(scripts) == 0 {
		return 0, nil
	}
	sort.Strings(scripts)

	if err := ensureHistoryTable(db); err != nil {
		return 0, err
	}
	history, err := loadHistory(db)
	if err != nil {
		return 0, err
	}

	executed := 0
	for _, name := range scripts {
		scriptPath := filepath.Join(dir, name)
		checksum, err := fileChecksum(scriptPath)
		if err != nil {
			return executed, fmt.Errorf("读取SQL脚本 %s 失败: %w", name, err)
		}
		if record, exists := history[name]; exists && record.Status == "SUCCESS" {
			if record.Checksum != checksum {
				logSkip(name, "已执行过但内容有变化，不重放（修正请放入新脚本）")
			} else {
				logSkip(name, "已执行过")
			}
			continue
		}
		log.Printf("执行SQL脚本: %s", name)
		if err := ExecuteScript(db, scriptPath); err != nil {
			// 失败也落记录：便于页面排查与下次重试
			if markErr := markScript(db, name, checksum, "FAILED", err.Error()); markErr != nil {
				log.Printf("记录脚本失败状态出错: %v", markErr)
			}
			return executed, err
		}
		if err := markScript(db, name, checksum, "SUCCESS", ""); err != nil {
			return executed, err
		}
		executed++
	}
	return executed, nil
}

// Dump 用 mysqldump 备份数据库到 outFile。
func Dump(db config.Database, outFile string) error {
	if err := os.MkdirAll(filepath.Dir(outFile), 0o755); err != nil {
		return fmt.Errorf("创建备份目录失败: %w", err)
	}
	out, err := os.Create(outFile)
	if err != nil {
		return fmt.Errorf("创建备份文件失败: %w", err)
	}
	defer out.Close()

	cmd := buildDBCommand(db, "mysqldump",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--single-transaction",
		"--default-character-set=utf8mb4",
		"--routines",
		"--triggers",
		db.Name,
	)
	cmd.Stdout = out
	stderr := &strings.Builder{}
	cmd.Stderr = stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("mysqldump 失败: %v, 输出: %s", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}
