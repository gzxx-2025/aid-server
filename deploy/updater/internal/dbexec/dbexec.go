// Package dbexec 通过 mysql/mysqldump 命令行工具执行 SQL 与数据库备份。
package dbexec

import (
	"crypto/sha256"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"aid-updater/internal/config"
)

// buildDBCommand 组装 mysql/mysqldump 命令：优先复用内置数据库容器；外部数据库
// 使用一次性客户端镜像；手动部署直接调用宿主机客户端。密码始终走 MYSQL_PWD
// 环境变量，不出现在命令行参数中。
func buildDBCommand(db config.Database, tool string, toolArgs ...string) *exec.Cmd {
	var cmd *exec.Cmd
	if strings.TrimSpace(db.ExecContainer) != "" {
		args := []string{"exec", "-i", "-e", "MYSQL_PWD", strings.TrimSpace(db.ExecContainer), tool}
		args = append(args, toolArgs...)
		cmd = exec.Command("docker", args...)
	} else if strings.TrimSpace(db.ClientImage) != "" {
		args := []string{"run", "--rm", "-i"}
		if network := strings.TrimSpace(db.DockerNetwork); network != "" {
			args = append(args, "--network", network)
		}
		args = append(args,
			"--add-host", "host.docker.internal:host-gateway",
			"-e", "MYSQL_PWD", strings.TrimSpace(db.ClientImage), tool)
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

func executeReader(db config.Database, reader io.Reader) error {
	cmd := buildDBCommand(db, "mysql",
		"--host", db.Host,
		"--port", fmt.Sprintf("%d", db.Port),
		"--user", db.User,
		"--default-character-set=utf8mb4",
		db.Name,
	)
	cmd.Stdin = reader
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("执行数据库语句失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
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
	sort.SliceStable(scripts, func(i, j int) bool {
		return migrationScriptLess(scripts[i], scripts[j])
	})

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

var migrationScriptPattern = regexp.MustCompile(`^v([0-9]+)\.([0-9]+)\.([0-9]+)(?:-(beta|rc)\.([0-9]+))?\.sql$`)

type migrationScriptVersion struct {
	major, minor, patch int
	channelRank         int
	channelNumber       int
	valid               bool
}

func parseMigrationScriptVersion(name string) migrationScriptVersion {
	match := migrationScriptPattern.FindStringSubmatch(strings.ToLower(name))
	if match == nil {
		return migrationScriptVersion{}
	}
	major, errMajor := strconv.Atoi(match[1])
	minor, errMinor := strconv.Atoi(match[2])
	patch, errPatch := strconv.Atoi(match[3])
	if errMajor != nil || errMinor != nil || errPatch != nil {
		return migrationScriptVersion{}
	}
	version := migrationScriptVersion{major: major, minor: minor, patch: patch, channelRank: 3, valid: true}
	if match[4] != "" {
		version.channelRank = 0
		if match[4] == "rc" {
			version.channelRank = 1
		}
		number, err := strconv.Atoi(match[5])
		if err != nil {
			return migrationScriptVersion{}
		}
		version.channelNumber = number
	}
	return version
}

func migrationScriptLess(left, right string) bool {
	lv := parseMigrationScriptVersion(left)
	rv := parseMigrationScriptVersion(right)
	if lv.valid != rv.valid {
		return lv.valid
	}
	if !lv.valid {
		return left < right
	}
	lparts := [...]int{lv.major, lv.minor, lv.patch, lv.channelRank, lv.channelNumber}
	rparts := [...]int{rv.major, rv.minor, rv.patch, rv.channelRank, rv.channelNumber}
	for index := range lparts {
		if lparts[index] != rparts[index] {
			return lparts[index] < rparts[index]
		}
	}
	return left < right
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

// Restore 从 mysqldump 文件恢复数据库。恢复内容通过标准输入传递，避免将 SQL 暴露到命令行。
func Restore(db config.Database, dumpFile string) error {
	f, err := os.Open(dumpFile)
	if err != nil {
		return fmt.Errorf("打开数据库备份失败: %w", err)
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
		return fmt.Errorf("恢复数据库失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

// RestoreClean 先清空当前 schema 中的视图和表，再导入升级前快照。
// 普通 mysqldump 只会覆盖快照中存在的对象，无法删除失败迁移新建的表；直接导入会使
// schema history 与真实结构脱节。调用方必须确保业务服务已经停止且 dumpFile 已验证存在。
func RestoreClean(db config.Database, dumpFile string) error {
	objects, err := runQuery(db, "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY CASE TABLE_TYPE WHEN 'VIEW' THEN 0 ELSE 1 END, TABLE_NAME")
	if err != nil {
		return fmt.Errorf("读取待恢复数据库对象失败: %w", err)
	}
	var ddl strings.Builder
	ddl.WriteString("SET FOREIGN_KEY_CHECKS=0;\n")
	for _, line := range strings.Split(strings.TrimSpace(objects), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		fields := strings.SplitN(line, "\t", 2)
		if len(fields) != 2 {
			return fmt.Errorf("无法解析数据库对象清单")
		}
		kind := "TABLE"
		if fields[1] == "VIEW" {
			kind = "VIEW"
		}
		fmt.Fprintf(&ddl, "DROP %s IF EXISTS %s;\n", kind, quoteIdentifier(fields[0]))
	}
	ddl.WriteString("SET FOREIGN_KEY_CHECKS=1;\n")
	if err := executeReader(db, strings.NewReader(ddl.String())); err != nil {
		return fmt.Errorf("清理失败迁移遗留对象失败: %w", err)
	}
	if err := Restore(db, dumpFile); err != nil {
		return err
	}
	return nil
}

// SchemaFingerprint 返回与业务数据无关的结构指纹，用于确认自动恢复后的数据库结构
// 与升级前快照一致。指纹覆盖表/视图、字段、索引、触发器和存储例程。
func SchemaFingerprint(db config.Database) (string, error) {
	queries := []string{
		"SELECT TABLE_NAME,TABLE_TYPE,IFNULL(ENGINE,''),IFNULL(TABLE_COLLATION,'') FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME",
		"SELECT TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'<NULL>'),EXTRA,IFNULL(CHARACTER_SET_NAME,''),IFNULL(COLLATION_NAME,'') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION",
		"SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME,NON_UNIQUE,INDEX_TYPE,IFNULL(SUB_PART,'') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",
		"SELECT TRIGGER_NAME,ACTION_TIMING,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME",
		"SELECT ROUTINE_NAME,ROUTINE_TYPE,DTD_IDENTIFIER,ROUTINE_DEFINITION FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() ORDER BY ROUTINE_TYPE,ROUTINE_NAME",
	}
	hash := sha256.New()
	for _, query := range queries {
		output, err := runQuery(db, query)
		if err != nil {
			return "", fmt.Errorf("计算数据库结构指纹失败: %w", err)
		}
		_, _ = hash.Write([]byte(output))
		_, _ = hash.Write([]byte{0})
	}
	return fmt.Sprintf("%x", hash.Sum(nil)), nil
}

func quoteIdentifier(value string) string {
	return "`" + strings.ReplaceAll(value, "`", "``") + "`"
}
