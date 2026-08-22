package task

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"aid-updater/internal/artifact"
	"aid-updater/internal/backup"
	"aid-updater/internal/config"
	"aid-updater/internal/dbexec"
	"aid-updater/internal/manifest"
	"aid-updater/internal/sysctl"
)

// 升级包内的固定布局。
const (
	pkgBackendDir = "backend"
	pkgAdminDir   = "admin-dist"
	pkgWebDir     = "web-dist"
	pkgSQLDir     = "sql"
	pkgBuildInfo  = "build-info.json"
)

// runApply 执行系统升级或版本回退：下载→校验→解压→备份→停服→替换→SQL→启动→健康检查，失败自动回滚。
func (r *Runner) runApply(ctx context.Context, t *Task, isRollback bool) error {
	if err := contextCancellationError(ctx); err != nil {
		return err
	}
	r.reportProgress(t, 3, "校验任务", "正在校验签名清单、版本与升级参数")
	var mirrors []string
	var sourceBuilder *manifest.SourceBuilderArtifact
	var err error
	buildFromSource := t.BuildFromSource
	if !buildFromSource && !isRollback {
		buildFromSource, err = sourceBuildFromSignedManifest(ctx, t)
		if err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return err
		}
		if buildFromSource {
			log.Printf("检测到旧版后台提交的升级任务，已按签名清单切换为源码构建: %s", t.TargetVersion)
		}
	}
	if buildFromSource {
		if isRollback {
			return fmt.Errorf("源码构建暂不支持版本回退")
		}
		if sourceBuilder, err = verifySourceBuildTask(ctx, t); err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return err
		}
	} else {
		if strings.TrimSpace(t.PackageURL) == "" || strings.TrimSpace(t.SHA256) == "" {
			return fmt.Errorf("任务缺少制品直链或校验值")
		}
		mirrors, err = verifyApplyTask(ctx, t, isRollback)
		if err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return err
		}
	}

	// 1. 按签名清单中的版本标签构建，或兼容旧任务下载并校验发布包
	archivePath := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("pkg-%s.tar.gz", t.TaskID))
	extractDir := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("extract-%s", t.TaskID))
	sourceWorkDir := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("source-%s", t.TaskID))
	sourceBuilderPath := filepath.Join(r.cfg.WorkDir, fmt.Sprintf("source-builder-%s.sh", t.TaskID))
	defer r.cleanupWork(archivePath, extractDir, sourceWorkDir, sourceBuilderPath, sourceBuilderPath+".part")

	if buildFromSource {
		r.reportProgress(t, 8, "构建源码", "正在拉取三端版本标签并编译，期间 CPU 占用会明显升高")
		if err := prepareTargetSourceBuilderContext(ctx, sourceBuilder, sourceBuilderPath); err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return err
		}
		if err := r.buildSourcePackage(ctx, t, archivePath, sourceWorkDir, sourceBuilderPath); err != nil {
			return err
		}
	} else {
		r.reportProgress(t, 8, "下载制品", "正在下载并校验目标版本发布包")
		sources := append([]string{t.PackageURL}, mirrors...)
		if _, _, err := artifact.DownloadAndVerifyContext(ctx, sources, archivePath, t.SHA256,
			time.Duration(r.cfg.DownloadTimeoutSeconds)*time.Second); err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return fmt.Errorf("下载升级包失败: %w", err)
		}
	}
	if err := contextCancellationError(ctx); err != nil {
		return err
	}
	r.reportProgress(t, 50, "校验制品", "目标版本制品已准备完成")

	// 2. 解压并校验包布局
	r.reportProgress(t, 53, "检查发布包", "正在解压并检查服务端、后台和 Web 产物")
	if err := artifact.ExtractTarGz(archivePath, extractDir); err != nil {
		return fmt.Errorf("解压升级包失败: %w", err)
	}
	packageRoot, err := locatePackageRoot(extractDir)
	if err != nil {
		return err
	}
	newJar, err := locateBackendJar(packageRoot)
	if err != nil {
		return err
	}
	if err := validateFrontendArtifacts(packageRoot, r.cfg); err != nil {
		return err
	}
	if err := contextCancellationError(ctx); err != nil {
		return err
	}
	r.reportProgress(t, 58, "检查发布包", "三端发布包结构校验通过")
	if !isRollback {
		r.reportProgress(t, 59, "检查运行环境", "正在按目标版本校验并准备运行环境")
		if err := r.preflightTargetRuntime(ctx, packageRoot); err != nil {
			if cancelErr := contextCancellationError(ctx); cancelErr != nil {
				return cancelErr
			}
			return fmt.Errorf("目标版本运行环境检查失败，尚未执行SQL或切换程序: %w", err)
		}
	}

	// 3. 数据库前置校验：需要执行 SQL 但未启用数据库配置时，提前失败（此时尚未停服，无损）
	sqlDir := filepath.Join(packageRoot, pkgSQLDir)
	rollbackScript := ""
	if isRollback {
		if t.DatabaseCompatible == nil || !*t.DatabaseCompatible {
			rollbackScript = strings.TrimSpace(t.DatabaseRollback)
			if rollbackScript == "" {
				return fmt.Errorf("目标版本数据库不兼容且未提供回退脚本")
			}
			if !r.cfg.Database.Enabled {
				return fmt.Errorf("回退需执行数据库脚本，请先在升级器配置中启用 database")
			}
			if filepath.Base(rollbackScript) != rollbackScript ||
				!strings.HasSuffix(strings.ToLower(rollbackScript), ".sql") {
				return fmt.Errorf("数据库回退脚本名称非法")
			}
			if _, statErr := os.Stat(filepath.Join(sqlDir, rollbackScript)); statErr != nil {
				return fmt.Errorf("回退包内缺少数据库回退脚本: %s", rollbackScript)
			}
		}
	} else if hasSQLScripts(sqlDir) && !r.cfg.Database.Enabled {
		return fmt.Errorf("升级包含数据库变更，请先在升级器配置中启用 database")
	}

	if err := r.closeCancellationWindow(ctx, t); err != nil {
		return err
	}

	// 4. 备份（含可选数据库备份；数据库备份必须先于任何 SQL 变更）；
	//    保留份数以后台「升级源配置」随任务下发的值优先，未下发时用本地配置
	if t.KeepBackups > 0 {
		r.cfg.KeepBackups = t.KeepBackups
	}
	tag := "upgrade"
	if isRollback {
		tag = "rollback"
	}
	r.reportProgress(t, 60, "创建备份", "正在备份三端产物、配置与数据库")
	snapshot, err := backup.Create(r.cfg, fmt.Sprintf("%s-%s", tag, t.TargetVersion))
	if err != nil {
		return fmt.Errorf("备份失败，已中止: %w", err)
	}
	log.Printf("备份完成: %s", snapshot.Dir)
	recoveryPath, err := r.createRecovery(t, snapshot)
	if err != nil {
		return err
	}
	databaseDirty := false
	r.reportProgress(t, 67, "创建备份", "升级前完整备份已创建")

	// 5. 升级的增量 SQL 在停服前执行（发布规范要求增量只做加法、与旧版本代码兼容），
	//    把停机窗口压缩到「替换文件 + 启动」；此时失败服务仍在运行，直接中止零影响。
	//    执行记录表（aid_schema_history）保证重试与跨版本包携带旧脚本时不会重复执行。
	if !isRollback && r.cfg.Database.Enabled && hasSQLScripts(sqlDir) {
		r.reportProgress(t, 70, "升级数据库", "正在执行未应用的增量 SQL")
		if err := markDatabaseDirty(recoveryPath); err != nil {
			return fmt.Errorf("更新恢复记录失败: %w", err)
		}
		databaseDirty = true
		count, err := dbexec.ExecuteDir(r.cfg.Database, sqlDir)
		if err != nil {
			if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
				log.Printf("恢复数据库前停止服务失败: %v", stopErr)
			}
			restoreErr := backup.RestoreDatabase(r.cfg, snapshot)
			startErr := startBackend(r.cfg)
			if restoreErr != nil {
				return fmt.Errorf("执行增量SQL失败(%v)，数据库恢复失败(%v)，备份目录: %s", err, restoreErr, snapshot.Dir)
			}
			if startErr != nil {
				return fmt.Errorf("执行增量SQL失败，数据库已恢复但服务启动失败: %v", startErr)
			}
			_ = os.Remove(recoveryPath)
			return fmt.Errorf("执行增量SQL失败，已恢复数据库: %w", err)
		}
		if count > 0 {
			log.Printf("已执行 %d 个增量SQL脚本", count)
		}
	}
	r.reportProgress(t, 75, "准备切换", "数据库检查完成，准备切换程序版本")

	// 6. 停服并替换产物；此后任何失败都走自动回滚
	if err := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); err != nil {
		return r.restoreAndReport(t, snapshot, fmt.Errorf("停止服务失败: %w", err), recoveryPath, databaseDirty)
	}
	r.reportProgress(t, 79, "切换版本", "后端已停止，正在原子替换三端产物")
	if err := r.replaceArtifacts(packageRoot, newJar); err != nil {
		return r.restoreAndReport(t, snapshot, err, recoveryPath, databaseDirty)
	}

	// 版本回退的数据库回退脚本可能收缩结构，必须在停服后执行
	if isRollback && rollbackScript != "" {
		if err := markDatabaseDirty(recoveryPath); err != nil {
			return r.restoreAndReport(t, snapshot, fmt.Errorf("更新恢复记录失败: %w", err), recoveryPath, false)
		}
		databaseDirty = true
		r.reportProgress(t, 83, "回退数据库", "正在执行目标版本数据库回退脚本")
		if err := dbexec.ExecuteScript(r.cfg.Database, filepath.Join(sqlDir, rollbackScript)); err != nil {
			return r.restoreAndReport(t, snapshot, fmt.Errorf("执行数据库回退脚本失败: %w", err), recoveryPath, databaseDirty)
		}
	}

	// 7. 启动并健康检查
	r.reportProgress(t, 86, "启动新版本", "正在启动后端并等待健康检查")
	if err := startBackend(r.cfg); err != nil {
		return r.restoreAndReport(t, snapshot, fmt.Errorf("启动服务失败: %w", err), recoveryPath, databaseDirty)
	}
	if err := sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second); err != nil {
		if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
			log.Printf("健康检查失败后停止服务失败: %v", stopErr)
		}
		return r.restoreAndReport(t, snapshot, fmt.Errorf("新版本健康检查失败: %w", err), recoveryPath, databaseDirty)
	}
	r.reportProgress(t, 92, "健康检查", "新版本后端健康检查通过")

	// 8. 重启附属服务使新产物生效：Docker 静态 Web 容器、网关 Nginx 等
	//    核心服务已健康后先提交恢复记录，避免清理失败导致下次启动误回滚。
	if err := markRecoveryCompleted(recoveryPath); err != nil {
		if stopErr := sysctl.StopService(r.cfg.Install.ServiceManager, r.cfg.Install.BackendService); stopErr != nil {
			log.Printf("提交恢复记录失败后停止服务失败: %v", stopErr)
		}
		return r.restoreAndReport(t, snapshot, fmt.Errorf("提交升级完成状态失败: %w", err), recoveryPath, databaseDirty)
	}
	r.reportProgress(t, 96, "刷新部署", "正在刷新部署脚本并重启 Web 与网关服务")
	deploymentAssetsRefreshed, refreshErr := r.refreshDeploymentAssets(packageRoot)
	var auxErr error
	if refreshErr != nil {
		log.Printf("核心升级已完成，但部署管理脚本刷新失败: %v", refreshErr)
		restartErr := restartAuxServices(r.cfg)
		if restartErr != nil {
			auxErr = fmt.Errorf("核心升级完成，但部署模板刷新失败(%v)，附属服务重启也失败(%v)", refreshErr, restartErr)
		} else {
			auxErr = fmt.Errorf("核心升级完成，但部署模板刷新失败: %w", refreshErr)
		}
	} else if deploymentAssetsRefreshed {
		auxErr = r.activateRefreshedDeploymentAssets()
	} else {
		auxErr = restartAuxServices(r.cfg)
	}
	if err := os.Remove(recoveryPath); err != nil && !os.IsNotExist(err) {
		log.Printf("清理已完成任务的恢复记录失败: %v", err)
	}
	if auxErr != nil {
		return auxErr
	}
	return nil
}

// preflightTargetRuntime 使用目标升级包内的管理脚本准备运行环境。它必须在创建
// 升级备份、执行 SQL 和替换产物之前完成，不能依赖升级完成后的最终重启兜底。
func (r *Runner) preflightTargetRuntime(ctx context.Context, packageRoot string) error {
	managerScript := filepath.Join(packageRoot, "installer", "deploy", "aid.sh")
	info, err := os.Lstat(managerScript)
	if err != nil {
		return fmt.Errorf("目标版本缺少部署管理脚本: %w", err)
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("目标版本部署管理脚本类型或权限非法")
	}

	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return fmt.Errorf("读取部署配置失败: %w", err)
	}
	dataRoot := filepath.Clean(filepath.Dir(filepath.Dir(r.cfg.Install.BackendJar)))
	dependencyMode := strings.TrimSpace(state.Values["DEPENDENCY_INSTALL_MODE"])
	if dependencyMode == "" {
		dependencyMode = "auto"
	}
	downloadTimeout := strings.TrimSpace(state.Values["DOWNLOAD_TIMEOUT_SECONDS"])
	if downloadTimeout == "" {
		downloadTimeout = "0"
	}

	baseEnv := environmentWithOverride(os.Environ(), "AID_DATA_ROOT", dataRoot)
	baseEnv = environmentWithOverride(baseEnv, "AID_DEPENDENCY_INSTALL_MODE", dependencyMode)
	baseEnv = environmentWithOverride(baseEnv, "AID_DOWNLOAD_TIMEOUT_SECONDS", downloadTimeout)
	baseEnv = environmentWithOverride(baseEnv, "AID_REMOTE_BOOTSTRAP", "0")

	var cmd *exec.Cmd
	helperContainerIDFile := ""
	switch r.cfg.Install.ServiceManager {
	case sysctl.ManagerSystemd:
		cmd = exec.CommandContext(ctx, "bash", managerScript, "__upgrade-runtime-preflight", "manual")
		cmd.Env = baseEnv
	case sysctl.ManagerDocker:
		runtimeImage, imageErr := targetDockerRuntimeImage(packageRoot)
		if imageErr != nil {
			return imageErr
		}
		runtimeReady, readyErr := targetDockerRuntimeReady(ctx, runtimeImage, managerScript, packageRoot, dataRoot, r.cfg.WorkDir)
		if readyErr != nil {
			return readyErr
		}
		if runtimeReady {
			log.Printf("目标版本 Docker 运行镜像已通过 JDK、FFmpeg 与中文字体校验: %s", runtimeImage)
			return nil
		}
		log.Printf("目标版本 Docker 运行镜像缺失或能力不完整，开始前置准备: %s", runtimeImage)
		// 当前升级器运行在 docker:27-cli 中。使用同镜像启动一次性工具容器，
		// 仅补齐 Bash/下载工具后执行目标版本脚本；数据根和 Docker Socket 均
		// 保持原路径，生成的固定运行镜像直接落到宿主机 Docker daemon。
		helperScript := `apk add --no-cache bash xz curl tar coreutils findutils >/dev/null && exec bash "$1" __upgrade-runtime-preflight docker`
		helperContainerIDFile = filepath.Join(r.cfg.WorkDir, "runtime-preflight.cid")
		_ = os.Remove(helperContainerIDFile)
		args := []string{
			"run", "--rm", "--cidfile", helperContainerIDFile, "--network", "host",
			"-v", "/var/run/docker.sock:/var/run/docker.sock",
			"-v", dataRoot + ":" + dataRoot,
			"-v", packageRoot + ":" + packageRoot + ":ro",
			"-e", "AID_DATA_ROOT=" + dataRoot,
			"-e", "AID_DEPENDENCY_INSTALL_MODE=" + dependencyMode,
			"-e", "AID_DOWNLOAD_TIMEOUT_SECONDS=" + downloadTimeout,
			"-e", "AID_REMOTE_BOOTSTRAP=0",
		}
		if dependencyRegion := strings.TrimSpace(state.Values["DEPENDENCY_REGION"]); dependencyRegion != "" {
			args = append(args, "-e", "AID_DEPENDENCY_REGION="+dependencyRegion)
		}
		if dockerMirrors := strings.TrimSpace(state.Values["DOCKER_MIRRORS"]); dockerMirrors != "" {
			args = append(args, "-e", "AID_DOCKER_MIRRORS="+dockerMirrors)
		}
		args = append(args, "docker:27-cli", "sh", "-eu", "-c", helperScript,
			"aid-runtime-preflight", managerScript)
		cmd = exec.CommandContext(ctx, "docker", args...)
		cmd.Env = baseEnv
	default:
		return fmt.Errorf("不支持的服务管理方式: %s", r.cfg.Install.ServiceManager)
	}

	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	configureCancellableCommand(cmd)
	if helperContainerIDFile != "" {
		defer cleanupDockerHelperContainer(helperContainerIDFile)
	}
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("目标版本运行环境准备失败: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if r.cfg.Install.ServiceManager == sysctl.ManagerDocker {
		runtimeImage, imageErr := targetDockerRuntimeImage(packageRoot)
		if imageErr != nil {
			return imageErr
		}
		runtimeReady, readyErr := targetDockerRuntimeReady(ctx, runtimeImage, managerScript, packageRoot, dataRoot, r.cfg.WorkDir)
		if readyErr != nil {
			return readyErr
		}
		if !runtimeReady {
			return fmt.Errorf("目标版本 Docker 运行镜像准备后复检失败")
		}
	}
	return nil
}

func cleanupDockerHelperContainer(cidFile string) {
	defer os.Remove(cidFile)
	raw, err := os.ReadFile(cidFile)
	if err != nil {
		return
	}
	containerID := strings.TrimSpace(string(raw))
	if len(containerID) < 12 || len(containerID) > 64 {
		return
	}
	for _, char := range containerID {
		if !((char >= '0' && char <= '9') || (char >= 'a' && char <= 'f')) {
			return
		}
	}
	_ = exec.Command("docker", "rm", "-f", containerID).Run()
}

// targetDockerRuntimeImage 从目标包的 aid-server 服务读取固定运行镜像。
func targetDockerRuntimeImage(packageRoot string) (string, error) {
	composePath := filepath.Join(packageRoot, "installer", "deploy", "docker", "docker-compose.yml")
	raw, err := os.ReadFile(composePath)
	if err != nil {
		return "", fmt.Errorf("目标版本缺少 Docker 编排文件: %w", err)
	}
	inServer := false
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimRight(line, "\r")
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(line, "  ") && !strings.HasPrefix(line, "    ") && strings.HasSuffix(trimmed, ":") {
			inServer = trimmed == "aid-server:"
			continue
		}
		if !inServer || !strings.HasPrefix(line, "    image:") {
			continue
		}
		image := strings.TrimSpace(strings.TrimPrefix(line, "    image:"))
		image = strings.Trim(image, "'\"")
		if image == "" || strings.ContainsAny(image, " \t\r\n") || strings.Contains(image, "${") ||
			!strings.HasPrefix(image, "aid/openjdk:") {
			return "", fmt.Errorf("目标版本 Docker 运行镜像配置非法")
		}
		return image, nil
	}
	return "", fmt.Errorf("目标版本 Docker 编排缺少 aid-server 运行镜像")
}

// targetDockerRuntimeReady 使用目标版本校验器检查镜像内的完整运行能力。
func targetDockerRuntimeReady(ctx context.Context, image, managerScript, packageRoot, dataRoot, workDir string) (bool, error) {
	inspect := exec.CommandContext(ctx, "docker", "image", "inspect", image)
	configureCancellableCommand(inspect)
	if err := inspect.Run(); err != nil {
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
		return false, nil
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	checkScript := `source "$1"; java -version 2>&1 | head -n 1 | grep -F "$JDK_VERSION" >/dev/null; configure_ffmpeg_runtime_paths; ffmpeg_runtime_usable >/dev/null; "$AID_FONT_ROOT/check-font.sh" validate >/dev/null`
	cidFile := filepath.Join(workDir, fmt.Sprintf("runtime-check-%d.cid", time.Now().UnixNano()))
	_ = os.Remove(cidFile)
	defer cleanupDockerHelperContainer(cidFile)
	cmd := exec.CommandContext(ctx, "docker", "run", "--rm", "--cidfile", cidFile,
		"-v", packageRoot+":"+packageRoot+":ro",
		"-v", dataRoot+":"+dataRoot+":ro",
		"-e", "AID_SH_LIBRARY_MODE=1",
		"-e", "AID_DATA_ROOT="+dataRoot,
		image, "bash", "-eu", "-c", checkScript, "aid-runtime-check", managerScript)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	configureCancellableCommand(cmd)
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return false, ctx.Err()
		}
		return false, nil
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	return true, nil
}

// activateRefreshedDeploymentAssets 让刚刷新的部署模板立即生效。手动模式必须
// 交给新版 aid.sh 完成旧 SSR → 静态站点迁移，不能读取旧配置里的 aid-web 重启项。
func (r *Runner) activateRefreshedDeploymentAssets() error {
	switch r.cfg.Install.ServiceManager {
	case sysctl.ManagerDocker:
		return r.reconcileDockerApplicationServices()
	case sysctl.ManagerSystemd:
		return restartManualApplicationWithManager(r.cfg)
	default:
		return restartAuxServices(r.cfg)
	}
}

// validateFrontendArtifacts 在备份、停服前确认两端入口齐全，避免不完整发布包
// 替换线上目录。Web 当前契约是 generate 后的纯静态 index.html + 200.html。
func validateFrontendArtifacts(packageRoot string, cfg *config.Config) error {
	if cfg.Install.AdminDist != "" && !fileExists(filepath.Join(packageRoot, pkgAdminDir, "index.html")) {
		return fmt.Errorf("升级包缺少管理端静态入口")
	}
	if cfg.Install.WebDist != "" {
		if !fileExists(filepath.Join(packageRoot, pkgWebDir, "index.html")) {
			return fmt.Errorf("升级包缺少Web静态入口")
		}
		if !fileExists(filepath.Join(packageRoot, pkgWebDir, "200.html")) {
			return fmt.Errorf("升级包缺少Web SPA通用入口")
		}
	}
	return nil
}

// refreshDeploymentAssets 刷新版本控制的部署脚本与 Docker 静态模板。
// 用户维护的 .env 永远跳过，数据库、上传文件和中间件数据目录也不在本函数范围内。
func (r *Runner) refreshDeploymentAssets(packageRoot string) (bool, error) {
	sourceDir := filepath.Join(packageRoot, "installer", "deploy")
	targetBuilder := strings.TrimSpace(r.cfg.SourceBuildScript)
	if targetBuilder == "" || !fileExists(filepath.Join(sourceDir, "build-release-from-source.sh")) {
		return false, nil
	}
	targetDir := filepath.Dir(targetBuilder)
	if err := os.MkdirAll(targetDir, 0o700); err != nil {
		return false, err
	}
	rootFiles := []string{
		"build-release-from-source.sh", "aid.sh", "README.md", "aid-deploy.conf.example",
		"aid-updater.config.example.json", "aid-updater.service", "install-updater.sh",
	}
	for _, name := range rootFiles {
		source := filepath.Join(sourceDir, name)
		if !fileExists(source) {
			continue
		}
		target := filepath.Join(targetDir, name)
		if err := backup.CopyFile(source, target); err != nil {
			return false, fmt.Errorf("刷新 %s 失败: %w", name, err)
		}
		if name == "aid.sh" || name == "build-release-from-source.sh" || name == "install-updater.sh" {
			if err := os.Chmod(target, 0o700); err != nil {
				return false, fmt.Errorf("设置 %s 权限失败: %w", name, err)
			}
		}
	}
	sourceDocker := filepath.Join(sourceDir, "docker")
	if !dirExists(sourceDocker) {
		return true, nil
	}
	targetDocker := filepath.Join(targetDir, "docker")
	err := filepath.WalkDir(sourceDocker, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		relative, err := filepath.Rel(sourceDocker, path)
		if err != nil {
			return err
		}
		if relative == "." {
			return os.MkdirAll(targetDocker, 0o755)
		}
		// .env 是管理员维护的唯一配置真源；仅同步不含密钥的 .env.example。
		if relative == ".env" {
			return nil
		}
		target := filepath.Join(targetDocker, relative)
		if entry.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		if !entry.Type().IsRegular() {
			return fmt.Errorf("部署模板包含非普通文件: %s", relative)
		}
		if err := backup.CopyFile(path, target); err != nil {
			return fmt.Errorf("刷新 Docker 部署模板 %s 失败: %w", relative, err)
		}
		return nil
	})
	if err != nil {
		return false, err
	}
	return true, nil
}

// reconcileDockerApplicationServices 使用新版 Compose 只重建三端业务容器。
// 不包含 aid-updater 和任何中间件，避免升级任务自杀或改动用户数据服务。
func (r *Runner) reconcileDockerApplicationServices() error {
	state, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return fmt.Errorf("读取Docker部署配置失败: %w", err)
	}
	services := []string{"aid-server", "aid-web", "nginx"}
	if deploymentProfileEnabled(state.Values["COMPOSE_PROFILES"], "https") {
		services = append(services, "nginx-https")
	}
	args := []string{"compose", "--env-file", state.ConfigPath, "-f", r.cfg.Deployment.ComposeFile,
		"up", "-d", "--no-deps", "--force-recreate"}
	args = append(args, services...)
	cmd := exec.Command("docker", args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("新版Docker编排生效失败: %v, 输出: %s", err, strings.TrimSpace(string(output)))
	}
	if err := sysctl.WaitHealthy(r.cfg.Install.HealthCheckURL,
		time.Duration(r.cfg.Install.HealthCheckTimeoutSeconds)*time.Second); err != nil {
		return fmt.Errorf("Docker业务容器重建后健康检查失败: %w", err)
	}
	log.Printf("新版Docker编排已生效，未重建数据库、Redis、RocketMQ与升级器容器")
	return nil
}

func sourceBuildModeForServiceManager(serviceManager string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(serviceManager)) {
	case sysctl.ManagerDocker:
		return "docker", nil
	case sysctl.ManagerSystemd:
		return "host", nil
	default:
		return "", fmt.Errorf("未知服务管理方式，无法选择源码构建模式: %s", serviceManager)
	}
}

var sourceBuildEnvironmentKeys = map[string]struct{}{
	"AID_DATA_ROOT":               {},
	"AID_SOURCE_BUILD_MODE":       {},
	"AID_DEPENDENCY_INSTALL_MODE": {},
	"AID_DEPENDENCY_REGION":       {},
	"AID_DOCKER_MIRRORS":          {},
}

const (
	sourceBuildModeCapability           = "AID_SOURCE_BUILD_MODE_CAPABILITY=explicit-v1"
	sourceBuildGovernorCapability       = "AID_BUILD_RESOURCE_CONTROL_CAPABILITY=governor-v1"
	maxSourceBuildScriptSize            = 1024 * 1024
	dockerSourceBuildToolAttemptTimeout = 15 * time.Minute
)

var (
	sourceBuildLookPath = exec.LookPath
	sourceBuildRun      = func(ctx context.Context, name string, args ...string) error {
		cmd := exec.CommandContext(ctx, name, args...)
		cmd.Stdout = log.Writer()
		cmd.Stderr = log.Writer()
		return cmd.Run()
	}
	sourceBuildInstallDockerTools = installDockerSourceBuildTools
	sourceBuildAlpineVersion      = detectAlpineRepositoryVersion
)

var dockerSourceBuildCommands = []string{"bash", "curl", "tar", "xz", "sha256sum", "find"}

var dockerSourceBuildPackages = []string{"bash", "curl", "tar", "xz", "coreutils", "findutils", "ca-certificates"}

// ensureSourceBuildInterpreter keeps Docker and manual upgrades on separate paths.
// Manual deployments already enter through aid.sh under Bash. The Docker updater
// deliberately uses the small docker:cli image, so it prepares only its own ephemeral
// container when the source builder needs Bash and required download utilities.
func ensureSourceBuildInterpreter(ctx context.Context, serviceManager string) (string, error) {
	if !strings.EqualFold(strings.TrimSpace(serviceManager), sysctl.ManagerDocker) {
		if bash, err := sourceBuildLookPath("bash"); err == nil {
			return bash, nil
		}
		return "", fmt.Errorf("源码构建环境缺少 Bash，请先通过最新 aid.sh 修复部署依赖")
	}

	missing := missingDockerSourceBuildCommands()
	if len(missing) == 0 {
		return sourceBuildLookPath("bash")
	}

	log.Printf("Docker 升级器构建环境缺少 %s，正在为受管升级器容器补齐必要工具", strings.Join(missing, ", "))
	if err := sourceBuildInstallDockerTools(ctx); err != nil {
		return "", fmt.Errorf("准备 Docker 源码构建工具失败: %w", err)
	}
	if missing = missingDockerSourceBuildCommands(); len(missing) > 0 {
		return "", fmt.Errorf("Docker 源码构建工具安装后仍缺少 %s", strings.Join(missing, ", "))
	}
	return sourceBuildLookPath("bash")
}

func missingDockerSourceBuildCommands() []string {
	missing := make([]string, 0, len(dockerSourceBuildCommands))
	for _, command := range dockerSourceBuildCommands {
		if _, err := sourceBuildLookPath(command); err != nil {
			missing = append(missing, command)
		}
	}
	return missing
}

func installDockerSourceBuildTools(ctx context.Context) error {
	apk, err := sourceBuildLookPath("apk")
	if err != nil {
		return fmt.Errorf("当前升级器容器既没有 Bash，也没有 apk 包管理器")
	}
	attempts := [][]string{{"add", "--no-cache"}}
	if alpineVersion := sourceBuildAlpineVersion(); alpineVersion != "" {
		for _, mirror := range []string{
			"https://mirrors.aliyun.com/alpine",
			"https://mirrors.cloud.tencent.com/alpine",
			"https://mirrors.tuna.tsinghua.edu.cn/alpine",
			"https://dl-cdn.alpinelinux.org/alpine",
		} {
			attempts = append(attempts, []string{
				"add", "--no-cache",
				"--repository", mirror + "/" + alpineVersion + "/main",
				"--repository", mirror + "/" + alpineVersion + "/community",
			})
		}
	}

	var lastErr error
	for index, baseArgs := range attempts {
		args := append(append([]string{}, baseArgs...), dockerSourceBuildPackages...)
		if index == 0 {
			log.Printf("使用升级器容器当前 Alpine 软件源准备源码构建工具")
		} else {
			log.Printf("切换 Alpine 备用软件源准备源码构建工具（第 %d/%d 路）", index, len(attempts)-1)
		}
		attemptCtx, cancel := context.WithTimeout(ctx, dockerSourceBuildToolAttemptTimeout)
		runErr := sourceBuildRun(attemptCtx, apk, args...)
		attemptErr := attemptCtx.Err()
		cancel()
		missing := missingDockerSourceBuildCommands()
		if len(missing) == 0 {
			if runErr != nil {
				log.Printf("Alpine 安装命令结束时返回 %v，但必要工具均已就绪，继续源码构建", runErr)
			}
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if attemptErr == context.DeadlineExceeded {
			lastErr = fmt.Errorf("当前软件源安装超过 %s，仍缺少 %s", dockerSourceBuildToolAttemptTimeout, strings.Join(missing, ", "))
			log.Printf("%v，自动切换下一条软件源", lastErr)
			continue
		}
		if runErr != nil {
			lastErr = runErr
		} else {
			lastErr = fmt.Errorf("安装命令完成后仍缺少 %s", strings.Join(missing, ", "))
		}
	}
	return fmt.Errorf("所有 Alpine 软件源均不可用: %w", lastErr)
}

func detectAlpineRepositoryVersion() string {
	raw, err := os.ReadFile("/etc/alpine-release")
	if err != nil {
		return ""
	}
	parts := strings.Split(strings.TrimSpace(string(raw)), ".")
	if len(parts) < 2 || !digitsOnly(parts[0]) || !digitsOnly(parts[1]) {
		return ""
	}
	return "v" + parts[0] + "." + parts[1]
}

func digitsOnly(value string) bool {
	if value == "" {
		return false
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return false
		}
	}
	return true
}

func sourceBuildEnvironment(base []string, dataRoot, sourceBuildMode, dependencyMode, dependencyRegion, dockerMirrors string) []string {
	env := make([]string, 0, len(base)+5)
	for _, item := range base {
		key, _, _ := strings.Cut(item, "=")
		if _, managed := sourceBuildEnvironmentKeys[key]; !managed {
			env = append(env, item)
		}
	}
	env = append(env,
		"AID_DATA_ROOT="+dataRoot,
		"AID_SOURCE_BUILD_MODE="+sourceBuildMode,
		"AID_DEPENDENCY_INSTALL_MODE="+dependencyMode,
		"AID_DEPENDENCY_REGION="+dependencyRegion)
	if sourceBuildMode == "docker" {
		env = append(env, "AID_DOCKER_MIRRORS="+dockerMirrors)
	}
	return env
}

func sourceBuildScriptSupportsExplicitMode(path string) (bool, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return false, err
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() <= 0 || info.Size() > maxSourceBuildScriptSize {
		return false, nil
	}
	file, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer file.Close()
	content, err := io.ReadAll(io.LimitReader(file, maxSourceBuildScriptSize+1))
	if err != nil {
		return false, err
	}
	if len(content) == 0 || len(content) > maxSourceBuildScriptSize {
		return false, nil
	}
	requiredMarkers := [][]byte{
		[]byte(sourceBuildModeCapability),
		[]byte(sourceBuildGovernorCapability),
		[]byte(`SOURCE_BUILD_MODE="${AID_SOURCE_BUILD_MODE:-auto}"`),
		[]byte(`case "$SOURCE_BUILD_MODE" in`),
	}
	for _, marker := range requiredMarkers {
		if !bytes.Contains(content, marker) {
			return false, nil
		}
	}
	return true, nil
}

// prepareTargetSourceBuilder downloads the exact builder covered by the signed
// target manifest. The installed builder may belong to the current/old release
// and is intentionally never used as an online-upgrade fallback.
func prepareTargetSourceBuilder(builder *manifest.SourceBuilderArtifact, script string) error {
	return prepareTargetSourceBuilderContext(context.Background(), builder, script)
}

func prepareTargetSourceBuilderContext(ctx context.Context, builder *manifest.SourceBuilderArtifact, script string) error {
	if builder == nil {
		return fmt.Errorf("签名清单缺少目标版本源码构建器")
	}
	if err := os.MkdirAll(filepath.Dir(script), 0o700); err != nil {
		return fmt.Errorf("创建源码构建器目录失败: %w", err)
	}
	temporary := script + ".part"
	_ = os.Remove(temporary)
	sources := append([]string{builder.URL}, builder.Mirrors...)
	selected, _, err := artifact.DownloadAndVerifyWithLimitContext(ctx, sources, temporary, builder.SHA256, 2*time.Minute, maxSourceBuildScriptSize)
	if err != nil {
		return fmt.Errorf("下载目标版本源码构建器失败: %w", err)
	}
	defer os.Remove(temporary)
	if err := os.Chmod(temporary, 0o700); err != nil {
		return fmt.Errorf("设置目标版本源码构建器权限失败: %w", err)
	}
	supported, err := sourceBuildScriptSupportsExplicitMode(temporary)
	if err != nil {
		return fmt.Errorf("读取目标版本源码构建器失败: %w", err)
	}
	if !supported {
		return fmt.Errorf("目标版本源码构建器缺少显式模式或资源治理能力")
	}
	if err := os.Rename(temporary, script); err != nil {
		return fmt.Errorf("落盘目标版本源码构建器失败: %w", err)
	}
	log.Printf("目标版本源码构建器已通过签名清单与 SHA256 校验: %s", selected)
	return nil
}

// buildSourcePackage executes the already verified target-release builder in
// an isolated work directory.
func (r *Runner) buildSourcePackage(parentContext context.Context, t *Task, archivePath, sourceWorkDir, script string) error {
	info, err := os.Lstat(script)
	if err != nil {
		return fmt.Errorf("源码构建脚本不可用: %w", err)
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0o022 != 0 {
		return fmt.Errorf("源码构建脚本类型非法")
	}
	supported, err := sourceBuildScriptSupportsExplicitMode(script)
	if err != nil {
		return fmt.Errorf("读取源码构建脚本失败: %w", err)
	}
	if !supported {
		return fmt.Errorf("目标版本源码构建器缺少显式模式或资源治理能力")
	}
	if err := os.MkdirAll(r.cfg.WorkDir, 0o700); err != nil {
		return fmt.Errorf("创建源码构建目录失败: %w", err)
	}
	sourceBuildMode, err := sourceBuildModeForServiceManager(r.cfg.Install.ServiceManager)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(parentContext,
		time.Duration(r.cfg.SourceBuildTimeoutSeconds)*time.Second)
	defer cancel()
	interpreter, err := ensureSourceBuildInterpreter(ctx, r.cfg.Install.ServiceManager)
	if err != nil {
		return err
	}
	cmd := exec.CommandContext(ctx, interpreter, script,
		"--version", t.TargetVersion,
		"--output", archivePath,
		"--work-dir", sourceWorkDir)
	configureCancellableCommand(cmd)
	deploymentState, err := r.cfg.ReadDeploymentState()
	if err != nil {
		return fmt.Errorf("读取依赖安装模式失败: %w", err)
	}
	dependencyMode := strings.TrimSpace(deploymentState.Values["DEPENDENCY_INSTALL_MODE"])
	if dependencyMode == "" {
		dependencyMode = "auto"
	}
	dependencyRegion := strings.TrimSpace(deploymentState.Values["DEPENDENCY_REGION"])
	if dependencyRegion == "" {
		dependencyRegion = "auto"
	}
	dockerMirrors := strings.TrimSpace(deploymentState.Values["DOCKER_MIRRORS"])
	downloadTimeout := strings.TrimSpace(deploymentState.Values["DOWNLOAD_TIMEOUT_SECONDS"])
	if downloadTimeout == "" {
		downloadTimeout = "0"
	}
	cmd.Env = sourceBuildEnvironment(os.Environ(),
		filepath.Dir(filepath.Dir(r.cfg.Install.BackendJar)), sourceBuildMode,
		dependencyMode, dependencyRegion, dockerMirrors)
	cmd.Env = environmentWithOverride(cmd.Env, "AID_DOWNLOAD_TIMEOUT_SECONDS", downloadTimeout)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	log.Printf("开始远程源码构建 AID %s（模式: %s）", t.TargetVersion, sourceBuildMode)
	if err := cmd.Run(); err != nil {
		if cancelErr := contextCancellationError(parentContext); cancelErr != nil {
			return cancelErr
		}
		if ctx.Err() == context.DeadlineExceeded {
			return fmt.Errorf("源码构建超时（%d秒）", r.cfg.SourceBuildTimeoutSeconds)
		}
		return fmt.Errorf("源码构建失败: %w", err)
	}
	if info, err := os.Stat(archivePath); err != nil || info.Size() == 0 {
		return fmt.Errorf("源码构建未生成有效安装包")
	}
	return nil
}

func verifyApplyTask(ctx context.Context, t *Task, isRollback bool) ([]string, error) {
	if strings.TrimSpace(t.ManifestURL) == "" {
		return nil, fmt.Errorf("任务缺少签名清单地址")
	}
	m, err := manifest.FetchContext(ctx, t.ManifestURL, 30*time.Second)
	if err != nil {
		return nil, fmt.Errorf("验证升级清单失败: %w", err)
	}
	if !isRollback {
		if mirrors, ok := m.ProductPackageMirrors(t.TargetVersion, t.PackageURL, t.SHA256); ok {
			return mirrors, nil
		}
		return nil, fmt.Errorf("升级任务与签名清单不一致")
	}
	if mirrors, ok := m.RollbackPackageMirrors(t.TargetVersion, t.PackageURL, t.SHA256); ok {
		return mirrors, nil
	}
	return nil, fmt.Errorf("回退任务不在签名清单中")
}

func verifySourceBuildTask(ctx context.Context, t *Task) (*manifest.SourceBuilderArtifact, error) {
	if strings.TrimSpace(t.ManifestURL) == "" {
		return nil, fmt.Errorf("任务缺少签名清单地址")
	}
	m, err := manifest.FetchContext(ctx, t.ManifestURL, 30*time.Second)
	if err != nil {
		return nil, fmt.Errorf("验证升级清单失败: %w", err)
	}
	builder, err := m.SelectSourceBuilderForVersion(t.TargetVersion)
	if err != nil {
		return nil, err
	}
	return builder, nil
}

// sourceBuildFromSignedManifest 兼容旧版后台生成的任务。只有清单签名有效且目标
// 版本明确声明 sourceBuild=true 时才切换，不能由任务里的 URL 或其他可变字段触发。
func sourceBuildFromSignedManifest(ctx context.Context, t *Task) (bool, error) {
	if strings.TrimSpace(t.ManifestURL) == "" {
		return false, nil
	}
	m, err := manifest.FetchContext(ctx, t.ManifestURL, 30*time.Second)
	if err != nil {
		return false, fmt.Errorf("验证升级清单失败: %w", err)
	}
	return m.MatchSourceBuildVersion(t.TargetVersion), nil
}

// restartAuxServices 依次重启配置的附属服务；未配置时跳过。
func restartAuxServices(cfg *config.Config) error {
	var failures []string
	for _, raw := range cfg.Install.RestartServices {
		service := strings.TrimSpace(raw)
		if service == "" {
			continue
		}
		if err := sysctl.StopService(cfg.Install.ServiceManager, service); err != nil {
			log.Printf("停止附属服务失败（请人工重启 %s）: %v", service, err)
			failures = append(failures, service+"停止失败")
		}
		if err := sysctl.StartService(cfg.Install.ServiceManager, service); err != nil {
			log.Printf("启动附属服务失败（请人工重启 %s）: %v", service, err)
			failures = append(failures, service+"启动失败")
			continue
		}
		log.Printf("附属服务已重启: %s", service)
	}
	if len(failures) > 0 {
		return fmt.Errorf("核心升级完成，但附属服务异常: %s", strings.Join(failures, ", "))
	}
	return nil
}

// replaceArtifacts 用包内产物替换部署位置的三端产物。
func (r *Runner) replaceArtifacts(packageRoot, newJar string) error {
	if err := backup.CopyFile(newJar, r.cfg.Install.BackendJar); err != nil {
		return fmt.Errorf("替换服务端jar失败: %w", err)
	}
	buildInfoSrc := filepath.Join(packageRoot, pkgBuildInfo)
	if fileExists(buildInfoSrc) {
		buildInfoDst := filepath.Join(filepath.Dir(r.cfg.Install.BackendJar), pkgBuildInfo)
		if err := backup.CopyFile(buildInfoSrc, buildInfoDst); err != nil {
			return fmt.Errorf("更新版本标记失败: %w", err)
		}
	}
	adminSrc := filepath.Join(packageRoot, pkgAdminDir)
	if r.cfg.Install.AdminDist != "" && dirExists(adminSrc) {
		if err := backup.ReplaceDir(adminSrc, r.cfg.Install.AdminDist); err != nil {
			return fmt.Errorf("替换管理端失败: %w", err)
		}
	}
	webSrc := filepath.Join(packageRoot, pkgWebDir)
	if r.cfg.Install.WebDist != "" && dirExists(webSrc) {
		if err := backup.ReplaceDir(webSrc, r.cfg.Install.WebDist); err != nil {
			return fmt.Errorf("替换用户端失败: %w", err)
		}
	}
	return nil
}

// locatePackageRoot 兼容"产物在压缩包根目录"与"产物在单层子目录"两种打包方式。
func locatePackageRoot(extractDir string) (string, error) {
	if dirExists(filepath.Join(extractDir, pkgBackendDir)) {
		return extractDir, nil
	}
	entries, err := os.ReadDir(extractDir)
	if err != nil {
		return "", fmt.Errorf("读取解压目录失败: %w", err)
	}
	var dirs []string
	for _, entry := range entries {
		if entry.IsDir() {
			dirs = append(dirs, entry.Name())
		}
	}
	if len(dirs) == 1 && dirExists(filepath.Join(extractDir, dirs[0], pkgBackendDir)) {
		return filepath.Join(extractDir, dirs[0]), nil
	}
	return "", fmt.Errorf("升级包布局非法: 缺少 %s/ 目录", pkgBackendDir)
}

// locateBackendJar 在包内 backend/ 下定位唯一的 jar。
func locateBackendJar(packageRoot string) (string, error) {
	backendDir := filepath.Join(packageRoot, pkgBackendDir)
	entries, err := os.ReadDir(backendDir)
	if err != nil {
		return "", fmt.Errorf("读取包内 backend 目录失败: %w", err)
	}
	var jars []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(strings.ToLower(entry.Name()), ".jar") {
			jars = append(jars, entry.Name())
		}
	}
	if len(jars) != 1 {
		return "", fmt.Errorf("包内 backend 目录应有且仅有一个jar, 实际 %d 个", len(jars))
	}
	return filepath.Join(backendDir, jars[0]), nil
}

func hasSQLScripts(dir string) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(strings.ToLower(entry.Name()), ".sql") {
			return true
		}
	}
	return false
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

func startBackend(cfg *config.Config) error {
	return sysctl.StartService(cfg.Install.ServiceManager, cfg.Install.BackendService)
}
