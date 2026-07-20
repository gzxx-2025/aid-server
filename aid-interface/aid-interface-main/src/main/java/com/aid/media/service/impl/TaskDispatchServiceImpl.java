package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.enums.DispatchMode;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.model.ScheduleStrategy;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;
import com.aid.service.IAiModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 统一任务调度中心实现：按供应商/模型策略驱动异步任务的轮询节奏。
 * 支持 CALLBACK_FIRST（回调优先）和 POLL_ONLY（纯轮询）两种模式。
 */
@Slf4j
@Service
public class TaskDispatchServiceImpl implements TaskDispatchService {

    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final IAiModelConfigService aiModelConfigService;
    private final TaskCompletionService taskCompletionService;
    private final List<ImageProviderClient> imageProviderClients;
    private final List<VideoProviderClient> videoProviderClients;
    private final List<TextProviderClient> textProviderClients;
    private final List<com.aid.media.provider.AudioProviderClient> audioProviderClients;

    /**
     * OSS 持久化兜底入口：
     * 用 {@link Lazy} 打断和 {@link com.aid.media.service.impl.MediaGenerationServiceImpl} 的循环依赖
     * （MediaGenerationServiceImpl 已注入 TaskDispatchService）。
     * 仅在轮询终态进入 persistOssAndCallback 时调用，职责是把 origin_url → oss_url 落库并触发业务侧回填。
     */
    private final IMediaGenerationService mediaGenerationService;

    /** 扇入支撑：用于给「非阻塞出图/出视频」父任务在轮询子任务时续租，防止僵尸回收误判退款。 */
    private final com.aid.rps.queue.MediaGenFanInSupport mediaGenFanInSupport;

    /** 合成分支收口服务：COMPOSE 任务轮询到"处理中"时回写真实进度到 aid_episode_editor */
    private final com.aid.compose.service.ComposeCompletionService composeCompletionService;

    public TaskDispatchServiceImpl(AidMediaTaskMapper aidMediaTaskMapper,
                                   IAiModelConfigService aiModelConfigService,
                                   TaskCompletionService taskCompletionService,
                                   List<ImageProviderClient> imageProviderClients,
                                   List<VideoProviderClient> videoProviderClients,
                                   List<TextProviderClient> textProviderClients,
                                   List<com.aid.media.provider.AudioProviderClient> audioProviderClients,
                                   @Lazy IMediaGenerationService mediaGenerationService,
                                   com.aid.rps.queue.MediaGenFanInSupport mediaGenFanInSupport,
                                   com.aid.compose.service.ComposeCompletionService composeCompletionService) {
        this.aidMediaTaskMapper = aidMediaTaskMapper;
        this.aiModelConfigService = aiModelConfigService;
        this.taskCompletionService = taskCompletionService;
        this.imageProviderClients = imageProviderClients;
        this.videoProviderClients = videoProviderClients;
        this.textProviderClients = textProviderClients;
        this.audioProviderClients = audioProviderClients;
        this.mediaGenerationService = mediaGenerationService;
        this.mediaGenFanInSupport = mediaGenFanInSupport;
        this.composeCompletionService = composeCompletionService;
    }

    @Override
    public void initDispatchSchedule(AidMediaTask task, AiModelConfigVo modelConfig) {
        ScheduleStrategy strategy = resolveStrategy(task, modelConfig);

        String dispatchMode;
        if (Objects.equals(task.getMediaType(), MediaType.TEXT.name())) {
            // 文本同步/流式不走调度中心。
            dispatchMode = DispatchMode.DIRECT.name();
        } else if (Boolean.TRUE.equals(strategy.getSupportsCallback())
            && DispatchMode.CALLBACK_FIRST.name().equals(strategy.getDispatchMode())) {
            dispatchMode = DispatchMode.CALLBACK_FIRST.name();
        } else {
            dispatchMode = DispatchMode.POLL_ONLY.name();
        }

        String snapshotJson = JSONUtil.toJsonStr(strategy);
        task.setDispatchMode(dispatchMode);
        task.setScheduleSnapshotJson(snapshotJson);

        Date now = new Date();
        if (DispatchMode.CALLBACK_FIRST.name().equals(dispatchMode)) {
            // 回调优先模式：设置回调等待截止时间。
            long deadlineMs = now.getTime() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
            task.setCallbackDeadline(new Date(deadlineMs));
            task.setStatus(MediaTaskStatus.WAIT_CALLBACK.name());
            // 同时设一个兜底 nextPollTime（回调截止后开始轮询）。
            task.setNextPollTime(new Date(deadlineMs));
        } else if (DispatchMode.POLL_ONLY.name().equals(dispatchMode)) {
            // 纯轮询模式：设置首次轮询时间。
            long firstPollMs = now.getTime() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
            task.setNextPollTime(new Date(firstPollMs));
            task.setStatus(MediaTaskStatus.WAIT_POLL.name());
        }
        // DIRECT 模式不设调度时间，不进入调度中心。
    }

    @Override
    public void initComposeDispatchSchedule(AidMediaTask task) {
        // 合成任务统一回调优先 + 轮询兜底：MPS 支持任务通知回调，回调到达即收口；回调超时转轮询。
        ScheduleStrategy strategy = new ScheduleStrategy();
        strategy.setDispatchMode(DispatchMode.CALLBACK_FIRST.name());
        strategy.setSupportsCallback(Boolean.TRUE);
        // 合成耗时较长（最长 60 分钟成片），首次轮询延迟与节奏放宽，最大存活留足余量。
        strategy.setFirstPollDelaySeconds(60);
        strategy.setBaseIntervalSeconds(30);
        strategy.setMaxIntervalSeconds(120);
        strategy.setBackoffFactor(1.5);
        strategy.setMaxRetryCount(120);
        strategy.setMaxLifeSeconds(5400);
        strategy.setProviderConcurrency(10);
        strategy.setModelConcurrency(5);

        task.setDispatchMode(DispatchMode.CALLBACK_FIRST.name());
        task.setScheduleSnapshotJson(JSONUtil.toJsonStr(strategy));
        long deadlineMs = System.currentTimeMillis() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
        task.setCallbackDeadline(new Date(deadlineMs));
        task.setNextPollTime(new Date(deadlineMs));
        task.setStatus(MediaTaskStatus.WAIT_CALLBACK.name());
    }

    @Override
    public int dispatchDueTasks(int batchSize) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.le(AidMediaTask::getNextPollTime, new Date());
        wrapper.isNotNull(AidMediaTask::getProviderTaskId);
        wrapper.ne(AidMediaTask::getProviderTaskId, "");
        wrapper.orderByAsc(AidMediaTask::getNextPollTime);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (AidMediaTask task : tasks) {
            try {
                dispatched += dispatchSingleTask(task) ? 1 : 0;
            } catch (Exception ex) {
                log.warn("dispatchDueTasks 单任务调度异常, taskId={}, error={}", task.getId(), ex.getMessage());
            }
        }
        return dispatched;
    }

    @Override
    public int transitionExpiredCallbacks(int batchSize) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name());
        wrapper.le(AidMediaTask::getCallbackDeadline, new Date());
        wrapper.orderByAsc(AidMediaTask::getCallbackDeadline);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int transitioned = 0;
        for (AidMediaTask task : tasks) {
            try {
                // 扇入型任务：回调等待期同样给父任务续租，覆盖 callback-first 且回调截止 > 父任务租约 TTL 的场景
                mediaGenFanInSupport.renewParentLeaseIfFanIn(task.getBizTaskType(), task.getBizTaskId());
                transitioned += transitionToPoll(task) ? 1 : 0;
            } catch (Exception ex) {
                log.warn("transitionExpiredCallbacks 异常, taskId={}, error={}", task.getId(), ex.getMessage());
            }
        }
        return transitioned;
    }

    @Override
    public int closeTimeoutTasks(int batchSize) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.isNotNull(AidMediaTask::getProviderTaskId);
        wrapper.ne(AidMediaTask::getProviderTaskId, "");
        wrapper.orderByAsc(AidMediaTask::getCreateTime);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        Date now = new Date();
        int closed = 0;
        for (AidMediaTask task : tasks) {
            ScheduleStrategy strategy = parseStrategySnapshot(task);
            if (Objects.isNull(strategy)) {
                continue;
            }
            long maxLifeMs = (long) strategy.getMaxLifeSeconds() * 1000L;
            Date createTime = task.getCreateTime();
            if (Objects.isNull(createTime)) {
                continue;
            }
            if (now.getTime() - createTime.getTime() > maxLifeMs) {
                closeTaskAsTimeout(task);
                closed++;
            }
        }
        return closed;
    }

    @Override
    public int closeStaleUnsubmittedTasks(int batchSize) {
        // 未提交上游的僵尸任务无调度快照（schedule_snapshot_json 为空），用固定兜底时限判定。
        // 取 2 小时：必须显著大于单次 HTTP 提交超时上限（SubmitTimeoutResolver 最大 3600s），
        // 否则可能把「提交仍在合法等待中」的任务误判为僵尸并退款（与 R11 超时分层一致：deadline > submitTimeout）。
        final long unsubmittedMaxLifeMs = 7200L * 1000L;
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.PENDING.name(),
            MediaTaskStatus.QUEUED.name());
        // 仅清「未提交上游」的：providerTaskId 为空（NULL 或空串）。
        wrapper.and(w -> w.isNull(AidMediaTask::getProviderTaskId)
            .or().eq(AidMediaTask::getProviderTaskId, ""));
        wrapper.orderByAsc(AidMediaTask::getCreateTime);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        Date now = new Date();
        int closed = 0;
        for (AidMediaTask task : tasks) {
            Date createTime = task.getCreateTime();
            if (Objects.isNull(createTime)) {
                continue;
            }
            if (now.getTime() - createTime.getTime() > unsubmittedMaxLifeMs) {
                boolean won = taskCompletionService.closeUnsubmittedTask(task.getId(), "任务超时: 提交未完成");
                log.info("closeStaleUnsubmittedTasks 关闭未提交僵尸任务, taskId={}, refundWon={}", task.getId(), won);
                closed++;
            }
        }
        return closed;
    }
    /**
     * 调度单个任务：查询上游并推进状态。
     */
    private boolean dispatchSingleTask(AidMediaTask task) {
        ScheduleStrategy strategy = parseStrategySnapshot(task);
        if (Objects.isNull(strategy)) {
            return false;
        }

        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (retryCount >= strategy.getMaxRetryCount()) {
            closeTaskAsTimeout(task);
            return true;
        }

        int nextInterval = calculateNextInterval(strategy, retryCount);
        Date nextPollTime = new Date(System.currentTimeMillis() + (long) nextInterval * 1000L);
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";

        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, task.getId());
        casWrapper.eq(AidMediaTask::getNextPollTime, task.getNextPollTime());
        casWrapper.set(AidMediaTask::getNextPollTime, nextPollTime);
        casWrapper.set(AidMediaTask::getRetryCount, retryCount + 1);
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());

        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            // CAS 失败：其他实例/线程已抢占此任务。
            return false;
        }

        // 扇入型任务（非阻塞出图/出视频）：轮询到该子任务即视为父任务"仍在途"，给父任务续租，
        // 防止「非阻塞提交后无线程续租 → 父任务租约过期被僵尸回收误判失败 + 退款」。
        mediaGenFanInSupport.renewParentLeaseIfFanIn(task.getBizTaskType(), task.getBizTaskId());

        ProviderTaskResult taskResult = queryUpstream(task);
        if (Objects.isNull(taskResult)) {
            // 上游无响应，保持当前状态等待下次调度。
            return false;
        }

        if (MediaTaskStatus.PROCESSING.name().equals(taskResult.getStatus())) {
            // COMPOSE 任务仍处理中：把 MPS 真实进度回写 aid_episode_editor.export_progress（展示增强，失败不阻断）
            if (Objects.equals(task.getMediaType(), com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE)) {
                composeCompletionService.onProgress(task, taskResult.getProgress());
            }
            return false;
        }

        boolean won = taskCompletionService.completeTask(task.getId(), taskResult);
        if (won && MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())) {
            // CAS 赢家执行 OSS 回写和业务回调。
            AidMediaTask refreshed = aidMediaTaskMapper.selectById(task.getId());
            if (Objects.nonNull(refreshed) && StrUtil.isNotBlank(refreshed.getOriginUrl())) {
                try {
                    persistOssAndCallback(refreshed);
                } catch (Exception ex) {
                    log.warn("dispatchSingleTask OSS 回写失败, taskId={}, error={}", task.getId(), ex.getMessage());
                }
            }
        }
        return true;
    }

    /**
     * 将回调超时任务转为轮询模式。
     */
    private boolean transitionToPoll(AidMediaTask task) {
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";

        // CAS 更新状态：WAIT_CALLBACK → WAIT_POLL。
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name());
        wrapper.set(AidMediaTask::getStatus, MediaTaskStatus.WAIT_POLL.name());
        // 设置 nextPollTime 为当前时间，立即开始轮询。
        wrapper.set(AidMediaTask::getNextPollTime, new Date());
        wrapper.set(AidMediaTask::getUpdateBy, userStr);
        wrapper.set(AidMediaTask::getUpdateTime, new Date());

        int rows = aidMediaTaskMapper.update(null, wrapper);
        if (rows > 0) {
            log.info("transitionToPoll 回调超时转轮询, taskId={}", task.getId());
        }
        return rows > 0;
    }

    /**
     * 关闭超时任务：构造失败结果，走统一终态入口。
     * 与回调/轮询共用 completeTask()，保证计费回写、并发释放、drainQueue 一致。
     */
    private void closeTaskAsTimeout(AidMediaTask task) {
        ProviderTaskResult timeoutResult = ProviderTaskResult.builder()
            .status(MediaTaskStatus.FAILED.name())
            .errorMessage("任务超时: 超过最大存活时间")
            .build();
        boolean won = taskCompletionService.completeTask(task.getId(), timeoutResult);
        log.info("closeTaskAsTimeout 任务超时关闭, taskId={}, completeTask={}", task.getId(), won);
    }

    /**
     * 查询上游任务状态。
     */
    private ProviderTaskResult queryUpstream(AidMediaTask task) {
        try {
            //    MPS 不在 aid_ai_model，故必须在 selectByModelCode 之前短路，避免因模型缺失误判为「无法查询」。
            if (Objects.equals(task.getMediaType(), com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE)) {
                VideoProviderClient composeClient = resolveVideoClient(task.getProtocol());
                if (Objects.isNull(composeClient)) {
                    log.warn("queryUpstream COMPOSE 未命中 MPS Provider, taskId={}, protocol={}",
                        task.getId(), task.getProtocol());
                    return null;
                }
                return composeClient.query(null, task.getProviderTaskId());
            }
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(task.getModelName());
            if (Objects.isNull(modelConfig)) {
                log.warn("queryUpstream 模型配置缺失, taskId={}, modelName={}", task.getId(), task.getModelName());
                return null;
            }

            if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
                ImageProviderClient client = resolveImageClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
                VideoProviderClient client = resolveVideoClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
                com.aid.media.provider.AudioProviderClient client = resolveAudioClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else {
                TextProviderClient client = resolveTextClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            }
        } catch (Exception ex) {
            log.warn("queryUpstream 查询上游异常, taskId={}, error={}", task.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 计算下次轮询间隔（指数退避 + 上限封顶）。
     */
    private int calculateNextInterval(ScheduleStrategy strategy, int retryCount) {
        double base = strategy.getBaseIntervalSeconds();
        double factor = strategy.getBackoffFactor();
        int max = strategy.getMaxIntervalSeconds();

        double interval = base * Math.pow(factor, retryCount);
        return (int) Math.min(interval, max);
    }

    /**
     * 解析调度策略：模型级 > 供应商级 > 媒体类型默认。
     * 模型级策略覆写轮询节奏参数，但 supportsCallback 默认继承供应商级。
     * 容错：{@code schedule_strategy_json} 列若被脏值污染（如误写成 UUID / 空白 / 非 JSON 文本），
     * 视为"未配置"静默跳过，不打 WARN 噪音；只有「以 {@code {} 开头但格式破坏的真 JSON」才告警，
     * 用以区分"运营没配"与"配置坏了"两种语义。
     */
    private ScheduleStrategy resolveStrategy(AidMediaTask task, AiModelConfigVo modelConfig) {
        if (modelConfig != null && looksLikeJsonObject(modelConfig.getScheduleStrategyJson())) {
            try {
                ScheduleStrategy strategy = JSONUtil.toBean(modelConfig.getScheduleStrategyJson(), ScheduleStrategy.class);
                if (Objects.nonNull(strategy) && StrUtil.isNotBlank(strategy.getDispatchMode())) {
                    // 模型级 JSON 未显式设置 supportsCallback 时，继承供应商级配置。
                    mergeProviderCallbackCapability(strategy, modelConfig);
                    log.debug("resolveStrategy 使用模型级策略, modelCode={}", modelConfig.getModelCode());
                    return strategy;
                }
            } catch (Exception ex) {
                log.warn("resolveStrategy 解析模型级策略异常, modelCode={}", modelConfig.getModelCode(), ex);
            }
        }

        if (modelConfig != null && looksLikeJsonObject(modelConfig.getProviderScheduleStrategyJson())) {
            try {
                ScheduleStrategy strategy = JSONUtil.toBean(modelConfig.getProviderScheduleStrategyJson(), ScheduleStrategy.class);
                if (Objects.nonNull(strategy) && StrUtil.isNotBlank(strategy.getDispatchMode())) {
                    // 供应商级策略直接使用其 supportsCallback。
                    mergeProviderCallbackCapability(strategy, modelConfig);
                    log.debug("resolveStrategy 使用供应商级策略, providerCode={}", modelConfig.getProviderCode());
                    return strategy;
                }
            } catch (Exception ex) {
                log.warn("resolveStrategy 解析供应商策略异常, providerCode={}", modelConfig.getProviderCode(), ex);
            }
        }

        ScheduleStrategy fallback;
        if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
            fallback = ScheduleStrategy.defaultVideo();
        } else if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
            fallback = ScheduleStrategy.defaultAudio();
        } else {
            fallback = ScheduleStrategy.defaultImage();
        }
        mergeProviderCallbackCapability(fallback, modelConfig);
        return fallback;
    }

    /**
     * 轻量启发式：判断 schedule_strategy_json 列值是否"看起来像 JSON 对象"。
     * 仅做形态校验（首字符是否 {@code {}），不做 JSON 完整性校验——后者交给 {@code JSONUtil.toBean}。
     * 用于跳过运营误填的 UUID / 普通字符串 / 空白等脏值，避免把"配置缺失"刷成 WARN。
     */
    private static boolean looksLikeJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }


    /**
     * 将供应商级 supportsCallback 能力合并到策略对象。
     * 只在策略的 supportsCallback 为 null（模型级 JSON 未显式设置）时才继承供应商值，
     * 显式 true/false 表示模型想开启/关闭回调能力，不覆盖。
     */
    private void mergeProviderCallbackCapability(ScheduleStrategy strategy, AiModelConfigVo modelConfig) {
        if (modelConfig == null) {
            return;
        }
        if (strategy.getSupportsCallback() == null) {
            strategy.setSupportsCallback(modelConfig.getSupportsCallback());
        }
    }

    /**
     * 从任务快照解析调度策略。
     */
    private ScheduleStrategy parseStrategySnapshot(AidMediaTask task) {
        if (StrUtil.isBlank(task.getScheduleSnapshotJson())) {
            return null;
        }
        try {
            return JSONUtil.toBean(task.getScheduleSnapshotJson(), ScheduleStrategy.class);
        } catch (Exception ex) {
            log.warn("parseStrategySnapshot 解析失败, taskId={}", task.getId(), ex);
            return null;
        }
    }

    /**
     * OSS 回写和业务回调：。
     */
    private void persistOssAndCallback(AidMediaTask task) {
        boolean persisted = false;
        try {
            persisted = mediaGenerationService.ensureOssPersisted(task.getId());
        } catch (Exception ex) {
            log.warn("persistOssAndCallback ensureOssPersisted 异常, taskId={}, err={}",
                task.getId(), ex.getMessage());
        }
        if (!persisted) {
            // 交给 mediaTask.ossCompensate 定时补偿兜底；补偿成功后会重新发布事件驱动业务回填。
            log.info("persistOssAndCallback 未就绪, 交由定时补偿兜底, taskId={}", task.getId());
            return;
        }
        log.info("persistOssAndCallback OSS 持久化完成, taskId={}, status={}",
            task.getId(), task.getStatus());
    }

    /**
     * 按 protocol 查找图片 provider。
     */
    private ImageProviderClient resolveImageClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<ImageProviderClient> candidates = imageProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找视频 provider。
     */
    private VideoProviderClient resolveVideoClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<VideoProviderClient> candidates = videoProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找文本 provider。
     */
    private TextProviderClient resolveTextClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<TextProviderClient> candidates = textProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找音频 provider。
     */
    private com.aid.media.provider.AudioProviderClient resolveAudioClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<com.aid.media.provider.AudioProviderClient> candidates = audioProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
