// Package dbexec 通过 mysql/mysqldump 命令行工具执行 SQL 与数据库备份。
package dbexec

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"aid-updater/internal/config"
)

// ExecuteScript 执行单个 SQL 文件。
func ExecuteScript(db config.Database, scriptPath string) error {
	f, err := os.Open(scriptPath)
	if err != nil {
		return fmt.Errorf("打开SQL脚本失败: %w", err)
	}
	defer f.Close()

	cmd := exec.Command("mysql",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--default-character-set=utf8mb4",
		db.Name,
	)
	// 密码经环境变量传递，避免出现在进程参数里被 ps 捕获
	cmd.Env = append(os.Environ(), "MYSQL_PWD="+db.Password)
	cmd.Stdin = f
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("执行SQL脚本 %s 失败: %v, 输出: %s",
			filepath.Base(scriptPath), err, strings.TrimSpace(string(output)))
	}
	return nil
}

// ExecuteDir 按文件名升序执行目录内全部 .sql 脚本，返回执行数量。
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
	sort.Strings(scripts)
	for _, name := range scripts {
		if err := ExecuteScript(db, filepath.Join(dir, name)); err != nil {
			return 0, err
		}
	}
	return len(scripts), nil
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

	cmd := exec.Command("mysqldump",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--single-transaction",
		"--default-character-set=utf8mb4",
		"--routines",
		"--triggers",
		db.Name,
	)
	cmd.Env = append(os.Environ(), "MYSQL_PWD="+db.Password)
	cmd.Stdout = out
	stderr := &strings.Builder{}
	cmd.Stderr = stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("mysqldump 失败: %v, 输出: %s", err, strings.TrimSpace(stderr.String()))
	}
	return nil
}
