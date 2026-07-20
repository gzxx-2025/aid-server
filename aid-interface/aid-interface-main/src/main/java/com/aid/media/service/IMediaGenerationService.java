package com.aid.media.service;

import com.aid.media.dto.MediaBatchGenerateRequest;
import com.aid.media.dto.MediaBatchGenerateResponse;
import com.aid.media.dto.MediaBatchProgressRequest;
import com.aid.media.dto.MediaBatchProgressResponse;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskListItem;
import com.aid.media.dto.MediaTaskListRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

/**
 * 统一媒体生成服务。
 */
public interface IMediaGenerationService {

    // 业务含义：创建图片任务，可能异步 PROCESSING 或同步 SUCCEEDED。
    MediaTaskResponse generateImage(MediaImageGenerateRequest request);

    /**
     * 按业务场景翻译为厂商协议字段。
     *
     * @param request  图片生成请求（实现类可能直接修改其 size / options）
     * @param scenario 场景标识，参见 {@link com.aid.media.constants.MediaImageScenario}
     */
    void applyImageScenarioOverrides(MediaImageGenerateRequest request, String scenario);

    // 业务含义：创建视频任务，可能异步 PROCESSING 或同步 SUCCEEDED。
    MediaTaskResponse generateVideo(MediaVideoGenerateRequest request);

    /**
     * 业务含义：创建音频（TTS）任务，目前统一为异步 PROCESSING（豆包 TTS 协议下无同步直出）。
     * 入参参见 {@link com.aid.media.dto.MediaAudioGenerateRequest}；
     * 返回的 {@link MediaTaskResponse} 进入 aid_media_task 调度体系，终态 audio_url 通过
     * {@code originUrl → ossUrl} 回写，业务侧监听 {@code MediaTaskCompletedEvent} 回填业务表。
     */
    MediaTaskResponse generateAudio(com.aid.media.dto.MediaAudioGenerateRequest request);

    /**
     * 业务含义：触发已落库的 COMPOSE 合成任务提交上游（复用统一并发/排队/调度机制）。
     *
     * @param taskId 已落库的 COMPOSE 任务ID
     */
    void submitComposeTaskAsync(Long taskId);

    // 业务含义：创建文本任务，内部走上游流式并聚合为整段后落库 result_text。
    MediaTaskResponse generateText(MediaTextGenerateRequest request);

    // 业务含义：文本流式生成，立即返回由 Controller 持有 SSE，业务线程推送增量与 taskId。
    void generateTextStream(MediaTextGenerateRequest request, MediaTextStreamSink sink);

    // 业务含义：查询任务状态，可选是否联动上游轮询。
    MediaTaskResponse queryTask(Long taskId, boolean pollRemote);

    /**
     * 查询任务当前状态（仅读本地 DB，不触发远端轮询、不累加重试计数）。
     * 用于内部编排场景（如 LLM 异步轮询）需要频繁检查终态但不应消耗补偿名额。
     *
     * @param taskId 任务ID
     * @return 任务快照，不存在时返回 null
     */
    MediaTaskResponse queryTaskLocal(Long taskId);

    /**
     * 远端刷新任务状态但不累加重试计数。
     * 用于服务端内部编排轮询（如 LLM 异步任务等待），每次调用都会查询上游 provider
     * 并更新本地 DB，但不会把 retry_count+1，避免高频轮询打满补偿上限。
     *
     * @param taskId 任务ID
     * @return 刷新后的任务快照，不存在时返回 null
     */
    MediaTaskResponse queryTaskRefresh(Long taskId);

    // 业务含义：一次提交多条图片/视频任务，同事务入库+扣费，提交后再异步调各厂商；返回 batchId 与初始任务列表。
    MediaBatchGenerateResponse batchGenerate(MediaBatchGenerateRequest request);

    // 业务含义：按 batchId 汇总当前用户该批进度，可选对 PROCESSING 任务拉上游状态，用于前端轮询百分比。
    MediaBatchProgressResponse queryBatchProgress(MediaBatchProgressRequest request);

    /**
     * 定时补偿轮询入口（供 Scheduler 调用）
     */
    int compensateProcessingTasks(int batchSize);

    /**
     * 排队任务兜底拉起（供 Scheduler 调用）：扫描 QUEUED 任务并逐条尝试抢占并发坑位后提交上游。
     * 正常路径由任务完成事件触发 drainQueue 拉起；本方法兜底"事件丢失 / 服务重启后无在途任务"场景，
     * 防止排队任务无人拉起、最终被未提交僵尸回收误判失败退款。
     *
     * @param batchSize 单轮最多扫描的排队任务条数，&lt;=0 时使用默认值
     * @return 本轮实际拉起的任务条数
     */
    int drainQueuedCompensate(int batchSize);

    /**
     * OSS 持久化补偿：扫描 status=SUCCEEDED 且 oss_url 为空的任务，
     * 重试下载上游产物并按 uploadMode 落地，成功后触发业务表回填。
     * 用于修复 persistOssIfNeeded 运行时瞬时失败（网络抖动、OSS 5xx）的记录。
     *
     * @param batchSize 本轮最大处理条数，&lt;=0 时使用默认值
     * @return 本轮实际处理条数
     */
    int compensateOssPersistence(int batchSize);

    /**
     * 终态任务 OSS 持久化兜底入口：。
     *
     * @param taskId aid_media_task.id
     * @return true 表示 oss_url 当前已就绪（本次新持久化成功或此前已有值）；false 表示仍需等待下轮补偿
     */
    boolean ensureOssPersisted(Long taskId);

    /**
     * 查询当前用户的媒体任务列表（支持按项目/剧集/类型/状态过滤）。
     *
     * @param request 查询条件（字段均可选）
     * @param userId  当前用户ID
     * @return 任务列表（精简字段，按创建时间倒序）
     */
    java.util.List<MediaTaskListItem> listUserTasks(MediaTaskListRequest request, Long userId);
}
