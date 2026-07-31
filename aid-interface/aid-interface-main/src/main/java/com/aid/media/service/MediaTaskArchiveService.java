package com.aid.media.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.config.AidAppConfig;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 媒体任务终态载荷治理：
 * 1. 按 aid_config 动态开关把压缩前的请求/响应异步写入本地 JSONL；
 * 2. 无论是否开启归档，都压缩终态 request_json 并清空 response_json；
 * 3. 业务扇入完成后清理 request_json 中已消费的分镜上下文。
 */
@Slf4j
@Service
public class MediaTaskArchiveService {

    /** aid_config 分类。 */
    private static final String CONFIG_CATEGORY = "media";
    /** aid_config 归档开关，缺失或非法时默认关闭。 */
    private static final String CONFIG_ARCHIVE_ENABLED = "media_task_archive_enabled";
    /** 动态开关本地缓存 10 秒，避免每个终态任务都查询数据库。 */
    private static final long CONFIG_CACHE_TTL_MILLIS = 10_000L;
    /** 单文件硬上限 100 MiB，配置值不允许突破。 */
    private static final long HARD_MAX_FILE_BYTES = 100L * 1024L * 1024L;
    /** 异步归档队列容量；队列满直接丢弃，不阻塞媒体主链路。 */
    private static final int ARCHIVE_QUEUE_CAPACITY = 1000;
    /** 应用关闭时最多等待归档线程退出的时间。 */
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    /** 归档时间格式。 */
    private static final DateTimeFormatter ARCHIVE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 文件日期格式。 */
    private static final DateTimeFormatter FILE_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IAidConfigService aidConfigService;
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /**
     * 相对于私有根目录的专属子路径。
     * 实际根目录由 aid.profile 派生为同级的 {@code <profile>-private}，避免被 /profile/** 暴露。
     */
    @Value("${aid.media-task-archive.sub-path:media-task-archive/request-response}")
    private String archiveSubPath;

    /** 单文件大小配置（MiB），最终仍受 100 MiB 硬上限约束。 */
    @Value("${aid.media-task-archive.max-file-size-mb:100}")
    private long configuredMaxFileSizeMb;

    /** 开关缓存。 */
    private volatile boolean cachedArchiveEnabled = false;
    private volatile long archiveConfigExpireAt = 0L;
    private final Object configRefreshMonitor = new Object();

    /** 单写线程保证每条 JSONL 不交叉，队列满时不阻塞业务线程。 */
    private final AtomicLong droppedArchiveCount = new AtomicLong(0L);
    private final ThreadPoolExecutor archiveExecutor = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(ARCHIVE_QUEUE_CAPACITY),
        runnable -> {
            Thread thread = new Thread(runnable, "media-task-archive-writer");
            thread.setDaemon(true);
            return thread;
        },
        (runnable, executor) -> {
            long dropped = droppedArchiveCount.incrementAndGet();
            log.warn("媒体任务归档队列已满，本次留档丢弃, dropped={}", dropped);
        }
    );

    /** 以下写入器字段仅由归档单线程访问，关闭时通过 writerMonitor 做互斥。 */
    private final Object writerMonitor = new Object();
    private final String writerInstanceId = UUID.randomUUID().toString().substring(0, 8);
    private BufferedWriter currentWriter;
    private LocalDate currentFileDate;
    private long currentFileBytes;
    private int currentPartIndex;

    public MediaTaskArchiveService(IAidConfigService aidConfigService,
                                   AidMediaTaskMapper aidMediaTaskMapper) {
        this.aidConfigService = aidConfigService;
        this.aidMediaTaskMapper = aidMediaTaskMapper;
    }

    /**
     * 准备终态数据库载荷。该方法只组装不可变归档快照，不执行磁盘 IO。
     *
     * @param task 当前媒体任务
     * @param targetStatus 即将写入的状态
     * @param rawResponseJson 本次上游原始响应
     * @return 数据库应写入的紧凑载荷与可选归档快照
     */
    public PreparedTerminalPayload prepareTerminalPayload(AidMediaTask task,
                                                          String targetStatus,
                                                          String rawResponseJson) {
        String safeRequestJson = MediaTaskPayloadSanitizer.sanitizeForStorage(
            Objects.isNull(task) ? null : task.getRequestJson());
        String safeResponseJson = MediaTaskPayloadSanitizer.sanitizeForStorage(rawResponseJson);
        if (Objects.isNull(task) || !isTerminal(targetStatus)) {
            return new PreparedTerminalPayload(safeRequestJson, safeResponseJson, null);
        }

        boolean firstTerminalCompaction =
            !MediaTaskPayloadSanitizer.isPayloadCompacted(safeRequestJson);
        String compactRequestJson = MediaTaskPayloadSanitizer.sanitizeForStorage(
            MediaTaskPayloadSanitizer.compactTerminalRequest(
                task.getMediaType(), targetStatus, safeRequestJson));

        ArchiveRecord archiveRecord = null;
        if (firstTerminalCompaction
            && (StrUtil.isNotBlank(safeRequestJson) || StrUtil.isNotBlank(safeResponseJson))
            && isArchiveEnabled()) {
            archiveRecord = ArchiveRecord.from(task, targetStatus, safeRequestJson, safeResponseJson);
        }
        // response_json 只保存上游原始快照，终态业务字段已拆列。使用空串保证 updateById 的
        // NOT_NULL 策略也会真实清空旧响应；LambdaUpdate 路径同样写空串，数据库不再保留响应正文。
        return new PreparedTerminalPayload(compactRequestJson, "", archiveRecord);
    }

    /**
     * 在当前事务提交后把快照放入异步队列；无事务上下文时立即入队。
     */
    public void archiveAfterCommit(PreparedTerminalPayload preparedPayload) {
        if (Objects.isNull(preparedPayload) || Objects.isNull(preparedPayload.archiveRecord)) {
            return;
        }
        Runnable enqueue = () -> archiveExecutor.execute(
            () -> writeArchiveRecord(preparedPayload.archiveRecord));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
            return;
        }
        enqueue.run();
    }

    /**
     * 分镜业务已消费上下文后再做第二阶段压缩，避免终态事件到达前提前删除上下文。
     */
    public void removeConsumedFanInContext(Long taskId, String contextKey) {
        if (Objects.isNull(taskId) || StrUtil.isBlank(contextKey)) {
            return;
        }
        try {
            // 特别标注：只查询上下文清理需要的主键、用户、状态和 request_json。
            AidMediaTask task = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                    .select(AidMediaTask::getId, AidMediaTask::getUserId,
                        AidMediaTask::getStatus, AidMediaTask::getRequestJson)
                    .eq(AidMediaTask::getId, taskId)
                    .last("LIMIT 1"));
            if (Objects.isNull(task) || !isTerminal(task.getStatus())) {
                return;
            }
            String compactRequest = MediaTaskPayloadSanitizer.removeConsumedFanInContext(
                task.getRequestJson(), contextKey);
            if (Objects.equals(compactRequest, task.getRequestJson())) {
                return;
            }
            String operator = Objects.isNull(task.getUserId()) ? "" : String.valueOf(task.getUserId());
            aidMediaTaskMapper.update(null,
                Wrappers.<AidMediaTask>lambdaUpdate()
                    .eq(AidMediaTask::getId, taskId)
                    .in(AidMediaTask::getStatus,
                        MediaTaskStatus.SUCCEEDED.name(), MediaTaskStatus.FAILED.name())
                    .set(AidMediaTask::getRequestJson, compactRequest)
                    .set(AidMediaTask::getUpdateBy, operator)
                    .set(AidMediaTask::getUpdateTime, new java.util.Date()));
        } catch (Exception exception) {
            // 上下文清理只做存储瘦身，不得影响已经完成的业务扇入。
            log.warn("媒体任务扇入上下文清理异常, taskId={}, contextKey={}",
                taskId, contextKey, exception);
        }
    }

    /**
     * 读取 aid_config 动态开关。10 秒内复用内存值；读取异常按关闭处理。
     */
    private boolean isArchiveEnabled() {
        long now = System.currentTimeMillis();
        if (now < archiveConfigExpireAt) {
            return cachedArchiveEnabled;
        }
        synchronized (configRefreshMonitor) {
            now = System.currentTimeMillis();
            if (now < archiveConfigExpireAt) {
                return cachedArchiveEnabled;
            }
            boolean enabled = false;
            try {
                // 特别标注：只读取媒体归档开关的 config_value，避免加载无关配置字段。
                AidConfig config = aidConfigService.getOne(
                    Wrappers.<AidConfig>lambdaQuery()
                        .select(AidConfig::getConfigValue)
                        .eq(AidConfig::getCategory, CONFIG_CATEGORY)
                        .eq(AidConfig::getConfigName, CONFIG_ARCHIVE_ENABLED)
                        .eq(AidConfig::getDelFlag, "0")
                        .last("LIMIT 1"), false);
                enabled = Objects.nonNull(config) && parseEnabled(config.getConfigValue());
            } catch (Exception exception) {
                log.warn("读取媒体任务归档开关失败，本轮按关闭处理, error={}", exception.getMessage());
            }
            cachedArchiveEnabled = enabled;
            archiveConfigExpireAt = now + CONFIG_CACHE_TTL_MILLIS;
            return enabled;
        }
    }

    private boolean parseEnabled(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String normalized = value.trim();
        return "true".equalsIgnoreCase(normalized)
            || "1".equals(normalized)
            || "yes".equalsIgnoreCase(normalized)
            || "on".equalsIgnoreCase(normalized);
    }

    private boolean isTerminal(String status) {
        return Objects.equals(status, MediaTaskStatus.SUCCEEDED.name())
            || Objects.equals(status, MediaTaskStatus.FAILED.name());
    }

    private void writeArchiveRecord(ArchiveRecord archiveRecord) {
        try {
            String line = toArchiveLine(archiveRecord);
            byte[] lineBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            long maxFileBytes = effectiveMaxFileBytes();
            if (lineBytes.length > maxFileBytes) {
                log.warn("媒体任务单条归档超过文件上限，已丢弃, taskId={}, bytes={}",
                    archiveRecord.taskId, lineBytes.length);
                return;
            }
            synchronized (writerMonitor) {
                ensureWriter(lineBytes.length, maxFileBytes);
                currentWriter.write(line);
                currentWriter.write('\n');
                currentWriter.flush();
                currentFileBytes += lineBytes.length;
            }
        } catch (Exception exception) {
            // 文件归档为可选审计能力，失败不回写数据库、不影响媒体任务主链路。
            synchronized (writerMonitor) {
                closeCurrentWriter();
            }
            log.warn("媒体任务请求响应归档失败, taskId={}, error={}",
                archiveRecord.taskId, exception.getMessage());
        }
    }

    private String toArchiveLine(ArchiveRecord record) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("taskId", record.taskId);
        putNullable(root, "userId", record.userId);
        putNullable(root, "projectId", record.projectId);
        putNullable(root, "episodeId", record.episodeId);
        putNullable(root, "mediaType", record.mediaType);
        putNullable(root, "status", record.status);
        putNullable(root, "modelName", record.modelName);
        putNullable(root, "providerTaskId", record.providerTaskId);
        putNullable(root, "requestHash", record.requestHash);
        putNullable(root, "bizTaskId", record.bizTaskId);
        putNullable(root, "bizTaskType", record.bizTaskType);
        root.put("archivedAt", ARCHIVE_TIME_FORMAT.format(record.archivedAt));
        root.set("requestJson", parsePayload(record.requestJson));
        root.set("responseJson", parsePayload(record.responseJson));
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private JsonNode parsePayload(String payload) {
        if (StrUtil.isBlank(payload)) {
            return OBJECT_MAPPER.nullNode();
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(payload);
            return Objects.isNull(node) ? OBJECT_MAPPER.nullNode() : node;
        } catch (Exception exception) {
            return OBJECT_MAPPER.getNodeFactory().textNode(payload);
        }
    }

    private void putNullable(ObjectNode root, String fieldName, Object value) {
        if (value instanceof Long longValue) {
            root.put(fieldName, longValue);
        } else if (Objects.nonNull(value)) {
            root.put(fieldName, String.valueOf(value));
        } else {
            root.putNull(fieldName);
        }
    }

    private void ensureWriter(long nextLineBytes, long maxFileBytes) throws IOException {
        LocalDate today = LocalDate.now();
        boolean rotate = Objects.isNull(currentWriter)
            || !Objects.equals(today, currentFileDate)
            || currentFileBytes + nextLineBytes > maxFileBytes;
        if (!rotate) {
            return;
        }
        closeCurrentWriter();
        Path archiveDirectory = resolveArchiveDirectory();
        Files.createDirectories(archiveDirectory);
        currentFileDate = today;
        currentFileBytes = 0L;
        currentPartIndex++;
        String fileName = "media-task-"
            + FILE_DATE_FORMAT.format(today)
            + "-" + writerInstanceId
            + "-" + String.format("%04d", currentPartIndex)
            + ".jsonl";
        Path file = archiveDirectory.resolve(fileName).normalize();
        if (!file.startsWith(archiveDirectory)) {
            throw new IOException("归档路径非法");
        }
        currentWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        log.info("媒体任务归档文件已创建, path={}", file);
    }

    /**
     * 归档不能放进 aid.profile 本身：该目录被 /profile/** 公开映射。
     * 因此从配置目录派生同级私有根目录，再拼媒体任务专属子路径。
     */
    private Path resolveArchiveDirectory() throws IOException {
        if (StrUtil.isBlank(AidAppConfig.getProfile())) {
            throw new IOException("存储目录未配置");
        }
        Path profile = Paths.get(AidAppConfig.getProfile()).toAbsolutePath().normalize();
        Path profileName = profile.getFileName();
        if (Objects.isNull(profileName)) {
            throw new IOException("存储目录非法");
        }
        Path privateRoot = profile.resolveSibling(profileName + "-private").normalize();
        Path archiveDirectory = privateRoot.resolve(archiveSubPath).normalize();
        if (!archiveDirectory.startsWith(privateRoot)) {
            throw new IOException("归档子路径非法");
        }
        return archiveDirectory;
    }

    private long effectiveMaxFileBytes() {
        long configuredBytes;
        try {
            configuredBytes = Math.multiplyExact(configuredMaxFileSizeMb, 1024L * 1024L);
        } catch (ArithmeticException exception) {
            configuredBytes = HARD_MAX_FILE_BYTES;
        }
        if (configuredBytes <= 0L) {
            return HARD_MAX_FILE_BYTES;
        }
        return Math.min(configuredBytes, HARD_MAX_FILE_BYTES);
    }

    @PreDestroy
    public void shutdownArchiveWriter() {
        archiveExecutor.shutdown();
        try {
            if (!archiveExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                archiveExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            archiveExecutor.shutdownNow();
        }
        synchronized (writerMonitor) {
            closeCurrentWriter();
        }
    }

    private void closeCurrentWriter() {
        if (Objects.isNull(currentWriter)) {
            return;
        }
        try {
            currentWriter.flush();
            currentWriter.close();
        } catch (IOException exception) {
            log.warn("关闭媒体任务归档文件失败, error={}", exception.getMessage());
        } finally {
            currentWriter = null;
            currentFileBytes = 0L;
        }
    }

    /**
     * 终态数据库载荷与可选文件归档快照。
     */
    public static final class PreparedTerminalPayload {
        private final String requestJson;
        private final String responseJson;
        private final ArchiveRecord archiveRecord;

        private PreparedTerminalPayload(String requestJson,
                                        String responseJson,
                                        ArchiveRecord archiveRecord) {
            this.requestJson = requestJson;
            this.responseJson = responseJson;
            this.archiveRecord = archiveRecord;
        }

        public String getRequestJson() {
            return requestJson;
        }

        public String getResponseJson() {
            return responseJson;
        }
    }

    /**
     * 单条归档记录只持有字符串快照，数据库后续压缩不会影响异步写盘内容。
     */
    private static final class ArchiveRecord {
        private final Long taskId;
        private final Long userId;
        private final Long projectId;
        private final Long episodeId;
        private final String mediaType;
        private final String status;
        private final String modelName;
        private final String providerTaskId;
        private final String requestHash;
        private final Long bizTaskId;
        private final String bizTaskType;
        private final String requestJson;
        private final String responseJson;
        private final LocalDateTime archivedAt;

        private ArchiveRecord(AidMediaTask task,
                              String status,
                              String requestJson,
                              String responseJson) {
            this.taskId = task.getId();
            this.userId = task.getUserId();
            this.projectId = task.getProjectId();
            this.episodeId = task.getEpisodeId();
            this.mediaType = task.getMediaType();
            this.status = status;
            this.modelName = task.getModelName();
            this.providerTaskId = task.getProviderTaskId();
            this.requestHash = task.getRequestHash();
            this.bizTaskId = task.getBizTaskId();
            this.bizTaskType = task.getBizTaskType();
            this.requestJson = requestJson;
            this.responseJson = responseJson;
            this.archivedAt = LocalDateTime.now();
        }

        private static ArchiveRecord from(AidMediaTask task,
                                          String status,
                                          String requestJson,
                                          String responseJson) {
            return new ArchiveRecord(task, status, requestJson, responseJson);
        }
    }
}
