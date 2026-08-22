package com.aid.upgrade.client;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.dto.DeploymentCheckVo;
import com.aid.upgrade.dto.DeploymentConfigVo;
import com.aid.upgrade.dto.UpdaterLastTaskVo;
import com.aid.upgrade.dto.UpdaterLogVo;
import com.aid.upgrade.dto.UpdaterStatusVo;
import com.alibaba.fastjson2.JSONObject;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 独立升级器（aid-updater）状态探测与任务投递客户端
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdaterClient {

    /** 未安装 */
    public static final String STATUS_NOT_INSTALLED = "NOT_INSTALLED";

    /** 已安装但未运行 */
    public static final String STATUS_STOPPED = "STOPPED";

    /** 运行正常，可一键升级 */
    public static final String STATUS_AVAILABLE = "AVAILABLE";

    /** 协议版本不兼容 */
    public static final String STATUS_INCOMPATIBLE = "INCOMPATIBLE";

    /** 状态文件异常 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 当前后端支持的升级器协议版本 */
    private static final int SUPPORTED_PROTOCOL_VERSION = 3;

    /** 单个 PEM 文件最大 1 MiB。 */
    private static final long MAX_CERTIFICATE_FILE_BYTES = 1024L * 1024L;

    /** 暂存文件最长保留一小时，防止升级器长期停机造成堆积。 */
    private static final long CERTIFICATE_STAGING_TTL_SECONDS = 3600L;

    /** 健康文件体积上限，防止误配大文件被整读 */
    private static final long MAX_HEALTH_FILE_BYTES = 64 * 1024L;

    /** 心跳过期阈值（毫秒）：超过视为升级器已停止，防止残留健康文件误判 */
    private static final long HEARTBEAT_STALE_MS = 60_000L;

    /** 升级器日志文件名（升级器写在健康文件同目录） */
    private static final String UPDATER_LOG_FILE_NAME = "updater.log";

    /** 日志尾部最大读取字节数（足够覆盖最近200行） */
    private static final int MAX_LOG_TAIL_BYTES = 64 * 1024;

    /** 日志最多返回行数 */
    private static final int MAX_LOG_LINES = 200;

    private final ConfigService configService;

    /**
     * 探测升级器当前状态
     *
     * @return 升级器状态（永不为null）
     */
    public UpdaterStatusVo detect() {
        UpdaterStatusVo vo = new UpdaterStatusVo();
        vo.setReady(false);
        File healthFile = new File(resolveHealthFilePath());
        if (!healthFile.exists() || !healthFile.isFile()) {
            vo.setStatus(STATUS_NOT_INSTALLED);
            vo.setMessage("未安装自动升级组件，无法使用页面一键升级，仍可手动升级。");
            return vo;
        }
        if (healthFile.length() > MAX_HEALTH_FILE_BYTES) {
            vo.setStatus(STATUS_UNKNOWN);
            vo.setMessage("升级器状态文件异常，请检查 aid-updater 安装。");
            return vo;
        }
        try {
            String content = FileUtil.readUtf8String(healthFile);
            JSONObject health = JSONObject.parseObject(content);
            String status = health.getString("status");
            Integer protocolVersion = health.getInteger("protocolVersion");
            vo.setVersion(health.getString("version"));
            vo.setProtocolVersion(protocolVersion);
            // 部署方式由升级器按自身配置上报（systemd=手动部署 / docker=容器部署）
            vo.setServiceManager(StrUtil.trimToNull(health.getString("serviceManager")));
            vo.setLastTask(parseLastTask(health));
            vo.setDeploymentConfig(parseDeploymentConfig(health));
            if (Objects.equals("RUNNING", status) && isHeartbeatStale(health, healthFile)) {
                // 升级器异常退出时健康文件可能残留 RUNNING，按心跳时间判定真实状态
                vo.setStatus(STATUS_STOPPED);
                vo.setMessage("升级器心跳超时，请检查 aid-updater 服务是否存活。");
            } else if (Objects.equals("RUNNING", status) && Objects.equals(protocolVersion, SUPPORTED_PROTOCOL_VERSION)) {
                vo.setStatus(STATUS_AVAILABLE);
                vo.setReady(true);
                vo.setMessage("升级器运行正常，可使用页面一键升级。");
            } else if (Objects.equals("RUNNING", status)) {
                vo.setStatus(STATUS_INCOMPATIBLE);
                vo.setMessage("升级器协议版本不兼容，请先更新 aid-updater。");
            } else {
                vo.setStatus(STATUS_STOPPED);
                vo.setMessage("升级器已安装但未运行，请启动 aid-updater 服务。");
            }
        } catch (Exception e) {
            // 状态文件损坏不影响系统运行，仅提示用户检查
            log.warn("解析升级器健康文件失败, path={}", healthFile.getPath(), e);
            vo.setStatus(STATUS_UNKNOWN);
            vo.setMessage("升级器状态文件解析失败，请检查 aid-updater 安装。");
        }
        return vo;
    }

    /**
     * 解析升级器提供的脱敏部署配置；密钥原文不会出现在健康文件中。
     */
    private DeploymentConfigVo parseDeploymentConfig(JSONObject health) {
        JSONObject configuration = health.getJSONObject("configuration");
        if (Objects.isNull(configuration)) {
            return null;
        }
        DeploymentConfigVo vo = new DeploymentConfigVo();
        vo.setMode(StrUtil.trimToNull(configuration.getString("mode")));
        vo.setConfigPath(StrUtil.trimToNull(configuration.getString("configPath")));
        vo.setDefaultConfigPath(StrUtil.trimToNull(configuration.getString("defaultConfigPath")));
        vo.setAllowedConfigRoot(StrUtil.trimToNull(configuration.getString("allowedConfigRoot")));
        Map<String, String> values = new HashMap<>();
        JSONObject valueObject = configuration.getJSONObject("values");
        if (Objects.nonNull(valueObject)) {
            valueObject.forEach((key, value) -> values.put(key, Objects.toString(value, "")));
        }
        vo.setValues(values);
        List<String> configuredSecrets = configuration.getList("configuredSecrets", String.class);
        vo.setConfiguredSecrets(Objects.isNull(configuredSecrets) ? List.of() : configuredSecrets);
        return vo;
    }

    /**
     * 解析升级器健康文件生效路径：配置缺失时回退与部署脚本一致的默认路径，保证零配置可用
     */
    private String resolveHealthFilePath() {
        String configured = readUpgradeConfig(UpgradeConfigKeys.KEY_UPDATER_HEALTH_FILE);
        return StrUtil.isBlank(configured) ? UpgradeConfigKeys.DEFAULT_UPDATER_HEALTH_FILE : configured;
    }

    /**
     * 解析升级器任务文件生效路径：配置缺失时回退与部署脚本一致的默认路径
     */
    private String resolveTaskFilePath() {
        String configured = readUpgradeConfig(UpgradeConfigKeys.KEY_UPDATER_TASK_FILE);
        return StrUtil.isBlank(configured) ? UpgradeConfigKeys.DEFAULT_UPDATER_TASK_FILE : configured;
    }

    /**
     * 原子写入升级器任务，避免升级器读取到未写完的JSON。
     *
     * @param task 任务内容
     */
    public synchronized void submitTask(JSONObject task) {
        String taskFilePath = resolveTaskFilePath();
        Path temporary = null;
        try {
            UpdaterStatusVo currentStatus = detect();
            UpdaterLastTaskVo runningTask = currentStatus.getLastTask();
            if (Objects.nonNull(runningTask) && Objects.equals("RUNNING", runningTask.getState())) {
                log.error("提交升级任务失败, 已有运行任务, taskId={}, action={}",
                        runningTask.getTaskId(), runningTask.getAction());
                throw new ServiceException("已有任务处理中");
            }
            Path target = Path.of(taskFilePath).toAbsolutePath().normalize();
            Path parent = target.getParent();
            if (Objects.isNull(parent)) {
                log.error("提交升级任务失败, 任务路径无父目录, path={}", target);
                throw new ServiceException("任务路径错误");
            }
            Files.createDirectories(parent);
            Path runningMarker = target.resolveSibling(target.getFileName() + ".running");
            if (Files.exists(target) || Files.exists(runningMarker)) {
                log.error("提交升级任务失败, 已有任务待处理, path={}", target);
                throw new ServiceException("已有任务处理中");
            }
            temporary = Files.createTempFile(parent, "upgrade-task-", ".tmp");
            Files.writeString(temporary, task.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
            temporary = null;
        } catch (FileAlreadyExistsException e) {
            log.error("提交升级任务失败, 已有任务待处理, path={}", taskFilePath);
            throw new ServiceException("已有任务处理中");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("提交升级任务失败, path={}", taskFilePath, e);
            throw new ServiceException("任务提交失败");
        } finally {
            // 写入或移动失败时清理残留临时文件，避免污染升级器收件目录
            if (Objects.nonNull(temporary)) {
                FileUtil.del(temporary.toFile());
            }
        }
    }

    /**
     * 请求安全取消当前系统版本任务。
     */
    public synchronized void cancelVersionTask() {
        Path temporary = null;
        try {
            UpdaterStatusVo currentStatus = detect();
            UpdaterLastTaskVo runningTask = currentStatus.getLastTask();
            boolean versionTask = Objects.nonNull(runningTask)
                    && (Objects.equals("UPGRADE", runningTask.getAction())
                            || Objects.equals("ROLLBACK", runningTask.getAction()));
            if (!versionTask || !Objects.equals("RUNNING", runningTask.getState())) {
                log.error("取消升级被拒绝, 当前没有运行中的系统版本任务");
                throw new ServiceException("无可取消任务");
            }
            if (!runningTask.isCancellable()) {
                log.error("取消升级被拒绝, 任务已进入不可取消阶段, taskId={}, phase={}",
                        runningTask.getTaskId(), runningTask.getPhase());
                throw new ServiceException("当前阶段不可取消");
            }
            Path taskFile = Path.of(resolveTaskFilePath()).toAbsolutePath().normalize();
            Path parent = taskFile.getParent();
            if (Objects.isNull(parent) || StrUtil.isBlank(runningTask.getTaskId())) {
                log.error("取消升级失败, 任务路径或任务ID无效, path={}", taskFile);
                throw new ServiceException("取消请求失败");
            }
            Files.createDirectories(parent);
            Path target = taskFile.resolveSibling(taskFile.getFileName() + ".cancel");
            JSONObject request = new JSONObject();
            request.put("taskId", runningTask.getTaskId());
            request.put("requestedAt", Instant.now().toString());
            temporary = Files.createTempFile(parent, "upgrade-cancel-", ".tmp");
            setOwnerOnlyPermissions(temporary, false);
            Files.writeString(temporary, request.toJSONString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            log.info("升级取消请求已提交, taskId={}, phase={}", runningTask.getTaskId(), runningTask.getPhase());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("提交升级取消请求失败", e);
            throw new ServiceException("取消请求失败");
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * 将证书对安全暂存到升级器收件目录并原子投递安装任务。
     */
    public synchronized void submitCertificateTask(JSONObject task, MultipartFile certificate, MultipartFile privateKey) {
        Path certificateFile = null;
        Path privateKeyFile = null;
        byte[] privateKeyBytes = null;
        try {
            byte[] certificateBytes = readPemUpload(certificate, false);
            privateKeyBytes = readPemUpload(privateKey, true);
            prepareCertificateStagingRoot();
            cleanupExpiredCertificateStaging();
            Path stagingRoot = resolveCertificateStagingRoot();
            certificateFile = stageSensitiveFile(stagingRoot, certificateBytes, "certificate-");
            privateKeyFile = stageSensitiveFile(stagingRoot, privateKeyBytes, "private-key-");
            task.put("certificateFile", certificateFile.toString());
            task.put("privateKeyFile", privateKeyFile.toString());
            submitTask(task);
            certificateFile = null;
            privateKeyFile = null;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("暂存 HTTPS 证书失败", e);
            throw new ServiceException("证书上传失败");
        } finally {
            deleteQuietly(certificateFile);
            deleteQuietly(privateKeyFile);
            if (Objects.nonNull(privateKeyBytes)) {
                Arrays.fill(privateKeyBytes, (byte) 0);
            }
        }
    }

    private byte[] readPemUpload(MultipartFile file, boolean privateKey) throws Exception {
        if (Objects.isNull(file) || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_CERTIFICATE_FILE_BYTES) {
            throw new ServiceException(privateKey ? "私钥大小不合规" : "证书大小不合规");
        }
        String filename = StrUtil.blankToDefault(file.getOriginalFilename(), "").toLowerCase();
        if (!filename.endsWith(".pem")) {
            throw new ServiceException("仅支持PEM文件");
        }
        byte[] content = file.getBytes();
        boolean markerFound = privateKey
                ? containsAscii(content, "-----BEGIN PRIVATE KEY-----")
                        || containsAscii(content, "-----BEGIN RSA PRIVATE KEY-----")
                        || containsAscii(content, "-----BEGIN EC PRIVATE KEY-----")
                : containsAscii(content, "-----BEGIN CERTIFICATE-----");
        if (!markerFound) {
            throw new ServiceException(privateKey ? "私钥格式错误" : "证书格式错误");
        }
        return content;
    }

    private boolean containsAscii(byte[] content, String marker) {
        byte[] expected = marker.getBytes(StandardCharsets.US_ASCII);
        for (int offset = 0; offset <= content.length - expected.length; offset++) {
            boolean matched = true;
            for (int index = 0; index < expected.length; index++) {
                if (content[offset + index] != expected[index]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private Path resolveCertificateStagingRoot() {
        Path taskFile = Path.of(resolveTaskFilePath()).toAbsolutePath().normalize();
        Path parent = taskFile.getParent();
        if (Objects.isNull(parent)) {
            throw new ServiceException("任务路径错误");
        }
        return parent.resolve("cert-staging").normalize();
    }

    private void prepareCertificateStagingRoot() throws Exception {
        Path stagingRoot = resolveCertificateStagingRoot();
        rejectSymlinkComponents(stagingRoot);
        Files.createDirectories(stagingRoot);
        rejectSymlinkComponents(stagingRoot);
        setOwnerOnlyPermissions(stagingRoot, true);
    }

    private Path stageSensitiveFile(Path stagingRoot, byte[] content, String prefix) throws Exception {
        Path temporary = Files.createTempFile(stagingRoot, prefix, ".tmp");
        try {
            setOwnerOnlyPermissions(temporary, false);
            Files.write(temporary, content);
            setOwnerOnlyPermissions(temporary, false);
            return temporary.toAbsolutePath().normalize();
        } catch (Exception e) {
            deleteQuietly(temporary);
            throw e;
        }
    }

    private void cleanupExpiredCertificateStaging() {
        Path stagingRoot = resolveCertificateStagingRoot();
        Instant threshold = Instant.now().minusSeconds(CERTIFICATE_STAGING_TTL_SECONDS);
        try (var paths = Files.list(stagingRoot)) {
            paths.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(threshold);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(this::deleteQuietly);
        } catch (Exception e) {
            log.warn("清理过期证书暂存文件失败");
        }
    }

    private void rejectSymlinkComponents(Path requested) throws Exception {
        Path current = requested.toAbsolutePath().normalize();
        while (Objects.nonNull(current)) {
            if (Files.isSymbolicLink(current)) {
                throw new ServiceException("证书目录不安全");
            }
            current = current.getParent();
        }
    }

    private void setOwnerOnlyPermissions(Path path, boolean directory) throws Exception {
        try {
            Set<PosixFilePermission> permissions = directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 开发环境无 POSIX 权限；Linux 生产环境必须成功收紧。
        }
    }

    private void deleteQuietly(Path path) {
        if (Objects.isNull(path)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("清理证书暂存文件失败");
        }
    }

    /**
     * 读取升级器最近运行日志（健康文件同目录 updater.log 尾部），供页面排查安装与升级问题
     *
     * @return 日志内容（永不为null；不可读时 lines 为空并携带原因）
     */
    public UpdaterLogVo readRecentLogs() {
        UpdaterLogVo vo = new UpdaterLogVo();
        vo.setLines(List.of());
        Path logFile = Path.of(resolveHealthFilePath()).toAbsolutePath().normalize()
                .resolveSibling(UPDATER_LOG_FILE_NAME);
        vo.setLogFile(logFile.toString());
        if (!Files.isRegularFile(logFile)) {
            vo.setMessage("暂无升级器日志（升级器尚未运行过）");
            return vo;
        }
        try {
            vo.setLines(readTailLines(logFile));
        } catch (Exception e) {
            log.error("读取升级器日志失败, path={}", logFile, e);
            vo.setMessage("日志读取失败");
        }
        return vo;
    }

    /**
     * 读取文件尾部若干行：只回读末尾固定字节，避免整读大文件
     */
    private List<String> readTailLines(Path file) throws Exception {
        long size = Files.size(file);
        int readBytes = (int) Math.min(size, MAX_LOG_TAIL_BYTES);
        byte[] buffer = new byte[readBytes];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(size - readBytes);
            raf.readFully(buffer);
        }
        String[] parts = new String(buffer, StandardCharsets.UTF_8).split("\r?\n");
        // 截断读取时首行可能不完整，丢弃
        int start = (size > readBytes && parts.length > 1) ? 1 : 0;
        List<String> lines = new ArrayList<>();
        for (int i = start; i < parts.length; i++) {
            if (StrUtil.isNotBlank(parts[i])) {
                lines.add(parts[i]);
            }
        }
        if (lines.size() > MAX_LOG_LINES) {
            return new ArrayList<>(lines.subList(lines.size() - MAX_LOG_LINES, lines.size()));
        }
        return lines;
    }

    /**
     * 判断健康文件心跳是否过期。新版协议优先使用与时区无关的 Epoch 毫秒；
     * 旧版升级器使用健康文件修改时间，避免容器缺少时区数据时产生八小时偏差。
     */
    private boolean isHeartbeatStale(JSONObject health, File healthFile) {
        Long heartbeatAt = health.getLong("updatedAtEpochMs");
        if (Objects.nonNull(heartbeatAt) && heartbeatAt > 0L) {
            return System.currentTimeMillis() - heartbeatAt > HEARTBEAT_STALE_MS;
        }
        long fileModifiedAt = healthFile.lastModified();
        return fileModifiedAt > 0L && System.currentTimeMillis() - fileModifiedAt > HEARTBEAT_STALE_MS;
    }

    /**
     * 解析健康文件中的最近任务结果，缺失或异常时返回null
     */
    private UpdaterLastTaskVo parseLastTask(JSONObject health) {
        JSONObject lastTask = health.getJSONObject("lastTask");
        if (Objects.isNull(lastTask)) {
            return null;
        }
        UpdaterLastTaskVo vo = new UpdaterLastTaskVo();
        vo.setTaskId(lastTask.getString("taskId"));
        vo.setAction(lastTask.getString("action"));
        vo.setState(lastTask.getString("state"));
        vo.setMessage(lastTask.getString("message"));
        Integer progress = lastTask.getInteger("progress");
        vo.setProgress(Objects.isNull(progress) ? 0 : Math.max(0, Math.min(100, progress)));
        vo.setPhase(lastTask.getString("phase"));
        vo.setStartedAt(lastTask.getString("startedAt"));
        vo.setUpdatedAt(lastTask.getString("updatedAt"));
        vo.setFinishedAt(lastTask.getString("finishedAt"));
        vo.setCancellable(Boolean.TRUE.equals(lastTask.getBoolean("cancellable")));
        vo.setCancelRequested(Boolean.TRUE.equals(lastTask.getBoolean("cancelRequested")));
        JSONObject checks = lastTask.getJSONObject("checks");
        if (Objects.nonNull(checks)) {
            Map<String, DeploymentCheckVo> parsed = new HashMap<>();
            checks.forEach((key, value) -> parsed.put(key,
                    JSONObject.from(value).to(DeploymentCheckVo.class)));
            vo.setChecks(parsed);
        }
        return vo;
    }

    /**
     * 读取升级器配置项
     */
    private String readUpgradeConfig(String key) {
        try {
            Map<String, String> configMap = configService.getConfigValues(UpgradeConfigKeys.CATEGORY_SYSTEM_UPGRADE);
            return StrUtil.trimToNull(configMap.get(key));
        } catch (Exception e) {
            // 分类未初始化视为未配置
            return null;
        }
    }
}
