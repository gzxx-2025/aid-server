package com.aid.rps.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.aid.oss.util.MediaPayloadUrlNormalizer;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.dto.CancelBatchRequest;
import com.aid.rps.dto.CancelBatchResult;
import com.aid.rps.dto.CancelTaskRequest;
import com.aid.rps.dto.TaskDetailRequest;
import com.aid.rps.dto.TaskDetailVO;
import com.aid.rps.dto.TaskListRequest;
import com.aid.rps.dto.TaskResumeRequest;
import com.aid.rps.assembler.TaskDetailAssembler;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.ITaskResumeService;
import com.aid.rps.sse.AssetExtractSseManager;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;

/**
 * C端通用任务Controller
 * 查询对象固定为 aid_extract_task（通用任务中心），适用任务类型：资产提取、形态生成、表单生成等。
 * 媒体生成（图片/视频）的 aid_media_task 不单独对外暴露查询接口：由各 storyboard/form service 在
 * 异步 worker 内部用 queryTaskRefresh 轮询，再统一通过本控制器的 SSE（/stream/{taskId}）对外推进度/终态。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/api/user/task")
public class UserTaskController extends BaseController
{
    @Resource
    private IAidExtractTaskService extractTaskService;

    /** 排队位次查询复用资产提取服务 */
    @Resource
    private IAssetExtractService assetExtractService;

    @Resource
    private AssetExtractSseManager sseManager;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private MediaPayloadUrlNormalizer mediaPayloadUrlNormalizer;

    /** 统一「继续生成（续生）」分发服务：按 task_type 路由到各类型续生实现 */
    @Resource
    private ITaskResumeService taskResumeService;

    @Resource
    private IWechatNotifyService wechatNotifyService;

    /**
     * 统一「继续生成（续生）」入口。
     *
     * @param request 入参（taskId 必填）
     * @return data: 对应类型续生实现返回的视图对象
     */
    @PostMapping("/resume")
    public AjaxResult resumeTask(@Valid @RequestBody TaskResumeRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            Object data = taskResumeService.resume(request.getTaskId(), userId);
            wechatNotifyService.notifyTaskStarted(request.getTaskId());
            return AjaxResult.success("提交成功", data);
        }
        catch (ServiceException e)
        {
            // 业务异常：message 已美化（≤12 字），打印堆栈便于排查后透传文案
            logger.error("统一续生业务异常: " + e.getMessage(), e);
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            // 非业务运行时异常（如 NPE）：message 可能为 null，统一兜底文案，不把 null 透传前端
            logger.error("统一续生失败", e);
            return error("续生失败");
        }
    }

    /**
     * 统一取消任务入口（与 {@link #resumeTask} 对称）。
     *
     * @param request 入参（taskId 必填）
     * @return data: 无，仅 msg 提示「操作成功」
     */
    @PostMapping("/cancel")
    public AjaxResult cancelTask(@Valid @RequestBody CancelTaskRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            assetExtractService.cancelTask(request.getTaskId(), userId);
            return success("操作成功");
        }
        catch (ServiceException e)
        {
            // 业务异常：message 已美化（≤12 字），打印堆栈便于排查后透传文案
            logger.error("统一取消业务异常: " + e.getMessage(), e);
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            // 非业务运行时异常：message 可能为 null，统一兜底文案，不把 null 透传前端
            logger.error("统一取消失败", e);
            return error("取消失败");
        }
    }

    /**
     * 批量取消任务（场景B：批量生图/高清/视频停止剩余）。
     * 只处理 PENDING/QUEUED 状态的任务，CAS 更新为 CANCELLED 并退款；
     * 已进入 PROCESSING 的任务会写入停止标记；出图/出片父任务会尽快进入可续生的暂停态。返回已受理停止/暂停数量。
     *
     * @param request 入参（taskIds 必填，不能为空）
     * @return data: {@link CancelBatchResult}，含已受理停止/暂停数量 cancelCount
     */
    @PostMapping("/cancel-batch")
    public AjaxResult cancelBatch(@Valid @RequestBody CancelBatchRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        try
        {
            CancelBatchResult result = assetExtractService.cancelBatchTasks(request.getTaskIds(), userId);
            return success(result);
        }
        catch (ServiceException e)
        {
            logger.error("批量取消业务异常: " + e.getMessage(), e);
            return error(e.getMessage());
        }
        catch (RuntimeException e)
        {
            logger.error("批量取消失败", e);
            return error("取消失败");
        }
    }

    /** Redis 轮询调度器（守护线程） */
    private static final ScheduledExecutorService POLL_SCHEDULER =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "sse-redis-poll");
                t.setDaemon(true);
                return t;
            });

    /** SSE 心跳间隔（秒）：每 N 秒推一个注释帧防止反代/防火墙断开 idle 连接 */
    private static final int SSE_HEARTBEAT_INTERVAL_SECONDS = 15;
    /** Redis 轮询周期（毫秒） */
    private static final long REDIS_POLL_INTERVAL_MS = 1500L;
    /**
     * SSE 连接超时（毫秒）：30 分钟。
     */
    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    /**
     * 构建 SSE 响应头：禁用 Nginx/反代缓冲、禁用客户端缓存、保持长连接。
     * 关键头说明：
     * - X-Accel-Buffering: no    Nginx 见此头会关闭对该响应的缓冲，否则前端要等连接关闭才一次性收到所有事件
     * - Cache-Control: no-cache  禁止浏览器/中间层缓存 SSE 流
     * - Connection: keep-alive   长连接保持
     */
    private HttpHeaders buildSseHeaders()
    {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-cache");
        headers.set("X-Accel-Buffering", "no");
        headers.setConnection("keep-alive");
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        return headers;
    }

    /**
     * 查询当前用户的通用任务列表（aid_extract_task，摘要字段）
     * 支持按 projectId、taskType、status 过滤，按创建时间倒序。
     * 不传请求体或传 {} 查全部。
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) TaskListRequest request)
    {
        Long userId = SecurityUtils.getUserId();

        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getUserId, userId);
        wrapper.eq(AidExtractTask::getDelFlag, "0");

        // 分页参数归一化：缺省第 1 页，每页 10 条，单页最大 50，防止一次性拉全部任务
        int pageNum = 1;
        int pageSize = 10;
        if (request != null)
        {
            if (Objects.nonNull(request.getProjectId()))
            {
                wrapper.eq(AidExtractTask::getProjectId, request.getProjectId());
            }
            if (StrUtil.isNotBlank(request.getTaskType()))
            {
                wrapper.eq(AidExtractTask::getTaskType, request.getTaskType());
            }
            if (StrUtil.isNotBlank(request.getStatus()))
            {
                wrapper.eq(AidExtractTask::getStatus, request.getStatus());
            }
            if (Objects.nonNull(request.getPageNum()) && request.getPageNum() >= 1)
            {
                pageNum = request.getPageNum();
            }
            if (Objects.nonNull(request.getPageSize()) && request.getPageSize() >= 1)
            {
                pageSize = Math.min(request.getPageSize(), 50);
            }
        }

        // 列表只查摘要字段，不含 resultData / inputSnapshot 等大字段。
        wrapper.select(AidExtractTask::getId,
            AidExtractTask::getProjectId,
            AidExtractTask::getEpisodeId,
            AidExtractTask::getTaskType,
            AidExtractTask::getStatus,
            AidExtractTask::getErrorMessage,
            AidExtractTask::getTotalCount,
            AidExtractTask::getModelCode,
            AidExtractTask::getCreateTime,
            AidExtractTask::getUpdateTime);

        wrapper.orderByDesc(AidExtractTask::getCreateTime);

        // 分页查询，返回 data + total + pageNum + pageSize（与其它 C 端分页接口一致）
        IPage<AidExtractTask> page = extractTaskService.page(new Page<>(pageNum, pageSize), wrapper);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("data", page.getRecords());
        ajax.put("total", page.getTotal());
        ajax.put("pageNum", page.getCurrent());
        ajax.put("pageSize", page.getSize());
        return ajax;
    }

    /**
     * 查询单条通用任务详情（aid_extract_task，按需查询必要字段）
     * 只能查自己的任务。SUCCEEDED 时返回 resultData，FAILED 时返回 errorMessage。
     */
    @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody TaskDetailRequest request)
    {
        Long userId = SecurityUtils.getUserId();
        AidExtractTask task = findOwnTaskForDetail(request.getTaskId(), userId);
        if (Objects.isNull(task))
        {
            return error("任务不存在");
        }
        TaskDetailVO vo = TaskDetailAssembler.toDetailVO(task);
        vo.setResultData(normalizeResultDataJson(vo.getResultData()));
        // QUEUED 任务补充实时排队位次
        if ("QUEUED".equals(task.getStatus()))
        {
            vo.setQueuePosition(assetExtractService.getTaskQueuePosition(task.getId()));
        }
        return success(vo);
    }

    /**
     * SSE 实时推送任务进度（aid_extract_task）
     * 只能订阅自己的任务。前端连接后可实时接收 progress / complete / error / warning 事件。
     * 如果任务已终态（SUCCEEDED/FAILED），立即补发终态事件并关闭连接。
     * 未终态（PENDING/QUEUED/PROCESSING）才注册 emitter 等待实时推送，连接超时 30 分钟。
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamTaskProgress(@PathVariable Long taskId)
    {
        Long userId = SecurityUtils.getUserId();
        HttpHeaders headers = buildSseHeaders();

        AidExtractTask task = findOwnTaskForStream(taskId, userId);
        if (Objects.isNull(task))
        {
            SseEmitter emitter = new SseEmitter(0L);
            trySendError(emitter, taskId, "任务不存在");
            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        if ("SUCCEEDED".equals(task.getStatus()))
        {
            SseEmitter emitter = new SseEmitter(0L);
            trySendComplete(emitter, taskId, task.getResultData());
            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        if ("FAILED".equals(task.getStatus()))
        {
            SseEmitter emitter = new SseEmitter(0L);
            trySendStructuredError(emitter, task);
            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        if ("CANCELLED".equals(task.getStatus()))
        {
            SseEmitter emitter = new SseEmitter(0L);
            trySendCancelled(emitter, taskId, "用户取消");
            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        // 部分失败为终态，补发已成功部分结果。
        if ("PARTIAL_FAILED".equals(task.getStatus()))
        {
            SseEmitter emitter = new SseEmitter(0L);
            trySendPartialFailed(emitter, taskId, task.getResultData());
            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        // QUEUED（排队中）也是未终态，需注册 emitter 让前端实时收排队位次 + 后续进度。
        String status = task.getStatus();
        if ("QUEUED".equals(status) || "PENDING".equals(status) || "PROCESSING".equals(status))
        {
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
            sseManager.register(taskId, emitter);

            // 立即推一个 SSE 注释帧，强制 flush headers + body 让 Nginx 立刻吐出 200 + headers
            tryFlushOpening(emitter);

            // 立即推首包：优先从 Redis 读最新快照，否则推连接成功初始事件
            // 如果首包已推终态（Redis 快照比 DB 先到终态），直接返回，不再启动轮询
            FirstPacketResult firstResult = sendFirstPacket(emitter, taskId);
            if (firstResult.terminal)
            {
                // 终态首包已 complete emitter，无需启动轮询，但仍需清 emitters map
                // 值比对删除，避免并发刷新重连时误删新连接的 emitter
                sseManager.unregister(taskId, emitter);
                return ResponseEntity.ok().headers(headers).body(emitter);
            }

            // 启动定时轮询 Redis 快照（每 1.5 秒），桥接跨进程进度
            // 把首包的 updateMillis 传入，避免轮询第一轮重复推送同一条快照
            startRedisPolling(emitter, taskId, firstResult.lastUpdateMillis);

            return ResponseEntity.ok().headers(headers).body(emitter);
        }

        logger.warn("任务状态异常, taskId={}, status={}", taskId, status);
        SseEmitter fallback = new SseEmitter(0L);
        trySendError(fallback, taskId, "任务状态异常");
        return ResponseEntity.ok().headers(headers).body(fallback);
    }

    /**
     * SSE 连接建立后立即推一个注释帧（`: opening\n\n`），强制 servlet 容器/反代刷出 200 状态码 + 响应头。
     * 否则部分反代要等到 body 第一次写入才把 headers 吐出来，前端短时间内看不到连接已建立。
     */
    private void tryFlushOpening(SseEmitter emitter)
    {
        try
        {
            emitter.send(SseEmitter.event().comment("opening"));
        }
        catch (Exception e)
        {
            logger.warn("SSE首帧 opening 注释推送失败", e);
        }
    }

    /**
     * 首包推送结果：terminal 表示是否已发送终态事件（complete/error/cancelled）；
     * lastUpdateMillis 用于轮询去重基准，避免轮询第一轮重复推送同一条 Redis 快照。
     */
    private static final class FirstPacketResult
    {
        final boolean terminal;
        final long lastUpdateMillis;

        FirstPacketResult(boolean terminal, long lastUpdateMillis)
        {
            this.terminal = terminal;
            this.lastUpdateMillis = lastUpdateMillis;
        }
    }

    /**
     * 按 taskId + userId + del_flag=0 查询，只查必要字段（detail 使用）。
     * 结构化错误字段不从 DB 读取，由 TaskDetailAssembler 运行时归一化。
     */
    private AidExtractTask findOwnTaskForDetail(Long taskId, Long userId)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getId, taskId);
        wrapper.eq(AidExtractTask::getUserId, userId);
        wrapper.eq(AidExtractTask::getDelFlag, "0");
        wrapper.select(AidExtractTask::getId,
            AidExtractTask::getProjectId,
            AidExtractTask::getEpisodeId,
            AidExtractTask::getTaskType,
            AidExtractTask::getStatus,
            AidExtractTask::getInputSnapshot,
            AidExtractTask::getResultData,
            AidExtractTask::getErrorMessage,
            AidExtractTask::getBillingStatus,
            AidExtractTask::getFrozenAmount,
            AidExtractTask::getTotalCount,
            AidExtractTask::getModelCode,
            AidExtractTask::getCreateTime,
            AidExtractTask::getUpdateTime);
        wrapper.last("LIMIT 1");
        return extractTaskService.getOne(wrapper);
    }

    /**
     * 查任务（stream 使用）：归属校验 + 终态补发所需字段。
     * 只查 status / resultData / errorMessage / billingStatus / frozenAmount，不查大字段 inputSnapshot。
     * 结构化错误字段不从 DB 读取，由 trySendStructuredError 运行时归一化。
     * userId 传 null 时不校验归属（仅限轮询内部已鉴权场景使用）。
     */
    private AidExtractTask findOwnTaskForStream(Long taskId, Long userId)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidExtractTask::getId, taskId);
        if (userId != null)
        {
            wrapper.eq(AidExtractTask::getUserId, userId);
        }
        wrapper.eq(AidExtractTask::getDelFlag, "0");
        wrapper.select(AidExtractTask::getId, AidExtractTask::getStatus,
                AidExtractTask::getResultData, AidExtractTask::getErrorMessage,
                AidExtractTask::getBillingStatus, AidExtractTask::getFrozenAmount);
        wrapper.last("LIMIT 1");
        return extractTaskService.getOne(wrapper);
    }

    /** 立即补发 complete 事件并关闭连接 */
    private void trySendComplete(SseEmitter emitter, Long taskId, String resultData)
    {
        trySendComplete(emitter, taskId, resultData, (List<Long>) null, null);
    }

    /**
     * 立即补发 complete 事件并关闭连接（断线重连 / 首包补发专用）。
     * 若快照中携带 {@code chainChildTaskId}（合并接口链式子任务），直接注入 complete 事件 payload，
     * 保证断线重连的前端也能拿到出图/出片子任务 ID（与实时推送口径一致，单事件无竞态）。
     */
    private void trySendComplete(SseEmitter emitter, Long taskId, String resultData,
                                 Long chainChildTaskId, String chainChildTaskType)
    {
        trySendComplete(emitter, taskId, resultData, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    private void trySendComplete(SseEmitter emitter, Long taskId, String resultData,
                                 List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try
        {
            Object data = StrUtil.isNotBlank(resultData)
                    ? objectMapper.readValue(resultData, Object.class) : null;
            Object payload = normalizePayload(withChain(withTaskId(data, taskId), chainChildTaskIds, chainChildTaskType));
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            // 客户端已断开（Broken pipe / ClientAbort）不是"结果异常"，
            // 不能再降级往同一个死连接写 error（必然二次失败 + 冒泡到全局异常处理器刷错误堆栈）。
            // 仅静默收尾即可。
            if (isClientAbort(e))
            {
                logger.info("SSE补发complete时客户端已断开，静默收尾");
                quietlyComplete(emitter, e);
                return;
            }
            // 双通道竞态：emitter 已被推送通道 complete，后到的轮询线程无需再写，静默收尾
            if (isAlreadyCompleted(e))
            {
                logger.info("SSE补发complete时连接已由其他通道关闭，静默收尾");
                quietlyComplete(emitter, e);
                return;
            }
            logger.warn("SSE补发complete失败, 降级为error事件", e);
            trySendError(emitter, taskId, "结果异常");
        }
    }

    /** 把链式子任务信息合并进终态事件 payload（withTaskId 已保证 payload 是 Map）。 */
    private Object withChain(Object payload, Long chainChildTaskId, String chainChildTaskType)
    {
        return withChain(payload, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    private Object withChain(Object payload, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        List<Long> safeTaskIds = normalizeTaskIds(chainChildTaskIds);
        Long chainChildTaskId = firstTaskId(safeTaskIds);
        if (chainChildTaskId != null && payload instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) payload;
            map.put("chainChildTaskId", chainChildTaskId);
            map.put("chainChildTaskIds", safeTaskIds);
            map.put("chainChildTaskType", chainChildTaskType);
        }
        return payload;
    }

    /**
     * 立即补发 partial_failed 终态事件并关闭连接。
     * PARTIAL_FAILED：部分子项成功、部分失败。携带 resultData 解析后的裸结果（含 successItems/failedItems），
     * 前端据此渲染已成功部分 + 判断续生入口。客户端断开时静默收尾，不降级二次写。
     */
    private void trySendPartialFailed(SseEmitter emitter, Long taskId, String resultData)
    {
        trySendPartialFailed(emitter, taskId, resultData, (List<Long>) null, null);
    }

    /**
     * 立即补发 partial_failed 终态事件并关闭连接（带链式子任务信息）。
     * 合并接口下部分失败时，已成功分镜照样会自动出图/出片，故 payload 也注入 chainChildTaskId。
     */
    private void trySendPartialFailed(SseEmitter emitter, Long taskId, String resultData,
                                      Long chainChildTaskId, String chainChildTaskType)
    {
        trySendPartialFailed(emitter, taskId, resultData, singletonTaskId(chainChildTaskId), chainChildTaskType);
    }

    private void trySendPartialFailed(SseEmitter emitter, Long taskId, String resultData,
                                      List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try
        {
            Object data = StrUtil.isNotBlank(resultData)
                    ? objectMapper.readValue(resultData, Object.class) : null;
            Object payload = normalizePayload(withChain(withTaskId(data, taskId), chainChildTaskIds, chainChildTaskType));
            emitter.send(SseEmitter.event()
                    .name("partial_failed")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            if (isClientAbort(e))
            {
                logger.info("SSE补发partial_failed时客户端已断开，静默收尾");
                quietlyComplete(emitter, e);
                return;
            }
            // 双通道竞态：emitter 已被其他通道 complete，静默收尾
            if (isAlreadyCompleted(e))
            {
                logger.info("SSE补发partial_failed时连接已由其他通道关闭，静默收尾");
                quietlyComplete(emitter, e);
                return;
            }
            logger.warn("SSE补发partial_failed失败", e);
            quietlyComplete(emitter, e);
        }
    }

    /** 立即补发 error 事件并关闭连接 */
    private void trySendError(SseEmitter emitter, Long taskId, String errorMessage)
    {
        try
        {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("taskId", taskId, "errorMessage", errorMessage), MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            emitter.completeWithError(e);
        }
    }

    /** 立即补发结构化 error 事件（payload 直接取自 Redis 快照,保持与 SSE 实时事件结构一致） */
    private void trySendErrorPayload(SseEmitter emitter, Long taskId, Map<String, Object> errorPayload)
    {
        try
        {
            Map<String, Object> payload = new LinkedHashMap<>(errorPayload);
            payload.putIfAbsent("taskId", taskId);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            emitter.completeWithError(e);
        }
    }

    /**
     * 补发结构化 error 事件（从 DB 任务实体构建）。
     * 运行时归一化：从 task.getErrorMessage() 实时派生结构化字段。
     */
    private void trySendStructuredError(SseEmitter emitter, com.aid.aid.domain.AidExtractTask task)
    {
        try
        {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("taskId", task.getId());
            // errorMessage 与结构化字段并存：只读 errorMessage 的前端拿到归一化后的友好文案
            if (task.getErrorMessage() != null)
            {
                com.aid.common.error.TaskErrorResult normalized =
                        com.aid.common.error.ErrorNormalizer.normalizeByMessage(task.getErrorMessage());
                payload.put("taskStatus", "FAILED");
                payload.put("errorCode", normalized.getErrorCode());
                payload.put("errorType", normalized.getErrorType());
                payload.put("errorSource", normalized.getErrorSource());
                payload.put("userMessage", normalized.getUserMessage());
                payload.put("rawMessage", task.getErrorMessage());
                payload.put("needRecharge", normalized.isNeedRecharge());
                payload.put("rechargeOwner", normalized.getRechargeOwner());
                payload.put("retryable", normalized.isRetryable());
                payload.put("billingStatus", task.getBillingStatus());
                payload.put("refundStatus", com.aid.common.error.RefundStatusMapper.resolveWithFrozen(
                        "FAILED", task.getBillingStatus(),
                        task.getFrozenAmount() != null && task.getFrozenAmount().signum() > 0));
                // errorMessage = 友好文案（前端可直接展示）
                payload.put("errorMessage", normalized.getUserMessage());
            }
            else
            {
                payload.put("errorMessage", "任务失败");
            }
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            emitter.completeWithError(e);
        }
    }

    /** 立即补发 cancelled 事件并关闭连接 */
    private void trySendCancelled(SseEmitter emitter, Long taskId, String message)
    {
        try
        {
            emitter.send(SseEmitter.event()
                    .name("cancelled")
                    .data(Map.of("taskId", taskId, "message", message), MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (Exception e)
        {
            emitter.completeWithError(e);
        }
    }

    /**
     * 终态结果补 taskId：Map 结果无损追加；数组/字符串等包进 data，保证所有 SSE 事件都可按 taskId 绑定。
     */
    private Object withTaskId(Object data, Long taskId)
    {
        if (data instanceof Map<?, ?> map)
        {
            Map<String, Object> payload = new LinkedHashMap<>();
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
            payload.putIfAbsent("taskId", taskId);
            return payload;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("data", data);
        return payload;
    }

    /** 下发前端前归一化 payload 中的媒体 URL 字段。 */
    private Object normalizePayload(Object payload)
    {
        return Objects.isNull(mediaPayloadUrlNormalizer) ? payload : mediaPayloadUrlNormalizer.normalize(payload);
    }

    private String normalizeResultDataJson(String resultData)
    {
        if (StrUtil.isBlank(resultData))
        {
            return resultData;
        }
        try
        {
            Object data = objectMapper.readValue(resultData, Object.class);
            return objectMapper.writeValueAsString(normalizePayload(data));
        }
        catch (Exception e)
        {
            logger.warn("任务结果媒体地址补全失败，返回原始resultData", e);
            return resultData;
        }
    }

    /**
     * SSE 连接建立后立即推首包。
     * 优先从 Redis 读取最新快照；没有则推默认连接成功事件。
     * 如果快照已是终态，立即发对应终态事件并关闭连接，不会当作 progress 推送。
     *
     * @return FirstPacketResult，包含是否已推终态 + 首包快照的 updateMillis（供轮询去重基准用）
     */
    private FirstPacketResult sendFirstPacket(SseEmitter emitter, Long taskId)
    {
        long seedUpdateMillis = 0L;
        try
        {
            String key = AssetExtractSseManager.buildProgressKey(taskId);
            Map<String, Object> snapshot = redisCache.getCacheObject(key);
            if (snapshot != null)
            {
                // 记录首包 updateMillis 作为轮询去重基准，避免轮询第一轮重复推送同一条快照
                Long um = resolveUpdateMillis(snapshot);
                if (um != null)
                {
                    seedUpdateMillis = um;
                }

                // 根据快照状态决定推什么事件
                String snapshotStatus = String.valueOf(snapshot.getOrDefault("status", ""));

                // 终态快照与 DB 状态互验：终态快照必然在 DB 终态提交之后写入，
                // 故「快照终态 + DB 已回到活跃态」只可能是续生/重跑前的残留快照（TTL 内未被新排队快照覆盖）。
                // 此时绝不能按终态补发（会误告知前端"任务已结束"并关闭连接），按无快照处理推连接成功事件；
                // seedUpdateMillis 照常返回，保证轮询也不会把这条残留快照再当新进度推送。
                boolean snapshotTerminal = "SUCCEEDED".equals(snapshotStatus) || "FAILED".equals(snapshotStatus)
                        || "CANCELLED".equals(snapshotStatus) || "PARTIAL_FAILED".equals(snapshotStatus);
                AidExtractTask dbTask = null;
                if (snapshotTerminal)
                {
                    dbTask = findOwnTaskForStream(taskId, null);
                    String dbStatus = dbTask == null ? null : dbTask.getStatus();
                    boolean dbActive = "PENDING".equals(dbStatus) || "QUEUED".equals(dbStatus)
                            || "PROCESSING".equals(dbStatus);
                    if (dbActive)
                    {
                        logger.info("SSE首包忽略续生前残留终态快照: taskId={}, snapshotStatus={}, dbStatus={}",
                                taskId, snapshotStatus, dbStatus);
                        emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(Map.of(
                                        "taskId", taskId,
                                        "stage", "connected",
                                        "progress", 0,
                                        "message", "连接成功，等待任务进度"
                                ), MediaType.APPLICATION_JSON));
                        return new FirstPacketResult(false, seedUpdateMillis);
                    }
                }

                // 终态快照：直接发终态事件并关闭连接
                if ("SUCCEEDED".equals(snapshotStatus))
                {
                    // 读取链式子任务 ID（合并接口 chain，断线重连时也能告知前端）
                    List<Long> chainChildTaskIds = resolveChainChildTaskIds(snapshot);
                    String chainChildTaskType = (String) snapshot.get("chainChildTaskType");
                    // 复用互验时已查询的任务行，避免重复查询
                    AidExtractTask task = dbTask;
                    if (task != null && StrUtil.isNotBlank(task.getResultData()))
                    {
                        trySendComplete(emitter, taskId, task.getResultData(), chainChildTaskIds, chainChildTaskType);
                    }
                    else
                    {
                        Object payload = normalizePayload(withChain(
                                withTaskId(Map.of("message", "任务完成"), taskId),
                                chainChildTaskIds, chainChildTaskType));
                        emitter.send(SseEmitter.event()
                                .name("complete")
                                .data(payload, MediaType.APPLICATION_JSON));
                        emitter.complete();
                    }
                    return new FirstPacketResult(true, seedUpdateMillis);
                }
                if ("FAILED".equals(snapshotStatus))
                {
                    // 优先用结构化 errorPayload，降级用 message
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorPayload = (Map<String, Object>) snapshot.get("errorPayload");
                    if (errorPayload != null)
                    {
                        trySendErrorPayload(emitter, taskId, errorPayload);
                    }
                    else
                    {
                        String msg = String.valueOf(snapshot.getOrDefault("message", "任务失败"));
                        trySendError(emitter, taskId, msg);
                    }
                    return new FirstPacketResult(true, seedUpdateMillis);
                }
                if ("CANCELLED".equals(snapshotStatus))
                {
                    String msg = String.valueOf(snapshot.getOrDefault("message", "用户取消"));
                    trySendCancelled(emitter, taskId, msg);
                    return new FirstPacketResult(true, seedUpdateMillis);
                }
                // 部分失败终态——从 DB 读 resultData 补发 partial_failed（复用互验时已查询的任务行）
                if ("PARTIAL_FAILED".equals(snapshotStatus))
                {
                    List<Long> chainChildTaskIds = resolveChainChildTaskIds(snapshot);
                    String chainChildTaskType = (String) snapshot.get("chainChildTaskType");
                    trySendPartialFailed(emitter, taskId, dbTask != null ? dbTask.getResultData() : null,
                            chainChildTaskIds, chainChildTaskType);
                    return new FirstPacketResult(true, seedUpdateMillis);
                }

                // 非终态快照：QUEUED 发 queued 事件，其余发 progress（与本地直推事件名统一）
                // 兜底补 taskId：旧快照或漏网写入路径可能没有 taskId，统一 putIfAbsent 保证契约"所有事件必带 taskId"
                snapshot.remove("@type"); // 剥离 Fastjson WriteClassName 写入的类型元字段，避免泄漏到前端 SSE payload
                snapshot.putIfAbsent("taskId", taskId);
                String firstEventName = "QUEUED".equals(snapshotStatus) ? "queued" : "progress";
                emitter.send(SseEmitter.event()
                        .name(firstEventName)
                        .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
            }
            else
            {
                // 无快照，推连接成功初始事件（必带 taskId，前端首包即可严格绑定订阅任务）
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(Map.of(
                                "taskId", taskId,
                                "stage", "connected",
                                "progress", 0,
                                "message", "连接成功，等待任务进度"
                        ), MediaType.APPLICATION_JSON));
            }
        }
        catch (Exception e)
        {
            logger.warn("SSE首包推送失败: taskId={}", taskId, e);
        }
        return new FirstPacketResult(false, seedUpdateMillis);
    }

    /**
     * 启动 Redis 轮询，每 1.5 秒读取最新快照并推送给前端，同时维持 SSE 心跳。
     *
     * @param emitter            SSE 发射器
     * @param taskId             任务ID
     * @param seedUpdateMillis   首包已推送的 updateMillis，作为轮询去重基准（避免轮询第一轮重复推同一条快照）
     */
    private void startRedisPolling(SseEmitter emitter, Long taskId, long seedUpdateMillis)
    {
        // 主去重：毫秒级时间戳，初始化为首包的 updateMillis
        final long[] lastUpdateMillis = { seedUpdateMillis };
        // 兜底去重：快照关键字段签名（用于无 updateMillis 的旧快照）
        final String[] lastSignature = { "" };
        // 心跳去重：上次成功向前端写出 SSE 帧的时间戳（含 progress / heartbeat / opening）
        final long[] lastWriteMillis = { System.currentTimeMillis() };
        final ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];

        ScheduledFuture<?> future = POLL_SCHEDULER.scheduleWithFixedDelay(() ->
        {
            try
            {
                String key = AssetExtractSseManager.buildProgressKey(taskId);
                Map<String, Object> snapshot = redisCache.getCacheObject(key);
                if (snapshot == null)
                {
                    // 没有快照：检查是否需要发心跳保持连接
                    maybeSendHeartbeat(emitter, taskId, lastWriteMillis);
                    return;
                }

                // 去重判断：优先 updateMillis，缺失时走签名兜底
                Long updateMillis = resolveUpdateMillis(snapshot);
                if (updateMillis != null)
                {
                    // 有 updateMillis：按毫秒级去重
                    if (updateMillis <= lastUpdateMillis[0])
                    {
                        // 没新快照：检查是否需要发心跳保持连接
                        maybeSendHeartbeat(emitter, taskId, lastWriteMillis);
                        return;
                    }
                    lastUpdateMillis[0] = updateMillis;
                }
                else
                {
                    // 无 updateMillis：按快照签名兜底去重，避免每轮重复推同一份旧快照
                    String signature = buildSnapshotSignature(snapshot);
                    if (signature.equals(lastSignature[0]))
                    {
                        // 没新快照：检查是否需要发心跳保持连接
                        maybeSendHeartbeat(emitter, taskId, lastWriteMillis);
                        return;
                    }
                    lastSignature[0] = signature;
                }

                String snapshotStatus = String.valueOf(snapshot.getOrDefault("status", ""));

                // 终态处理：推送终态事件后关闭连接
                if ("SUCCEEDED".equals(snapshotStatus))
                {
                    // 读取链式子任务 ID（合并接口 chain，轮询到终态时也要告知前端）
                    List<Long> chainChildTaskIds = resolveChainChildTaskIds(snapshot);
                    String chainChildTaskType = (String) snapshot.get("chainChildTaskType");
                    // 终态需要从数据库读 resultData 补发 complete
                    AidExtractTask task = findOwnTaskForStream(taskId, null);
                    if (task != null && StrUtil.isNotBlank(task.getResultData()))
                    {
                        trySendComplete(emitter, taskId, task.getResultData(), chainChildTaskIds, chainChildTaskType);
                    }
                    else
                    {
                        Object payload = normalizePayload(withChain(
                                withTaskId(Map.of("message", "任务完成"), taskId),
                                chainChildTaskIds, chainChildTaskType));
                        emitter.send(SseEmitter.event()
                                .name("complete")
                                .data(payload, MediaType.APPLICATION_JSON));
                        emitter.complete();
                    }
                    cancelPolling(futureHolder[0]);
                    return;
                }
                if ("FAILED".equals(snapshotStatus))
                {
                    // 优先用结构化 errorPayload，降级用 message
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorPayload = (Map<String, Object>) snapshot.get("errorPayload");
                    if (errorPayload != null)
                    {
                        trySendErrorPayload(emitter, taskId, errorPayload);
                    }
                    else
                    {
                        String msg = String.valueOf(snapshot.getOrDefault("message", "任务失败"));
                        trySendError(emitter, taskId, msg);
                    }
                    cancelPolling(futureHolder[0]);
                    return;
                }
                if ("CANCELLED".equals(snapshotStatus))
                {
                    String msg = String.valueOf(snapshot.getOrDefault("message", "用户取消"));
                    trySendCancelled(emitter, taskId, msg);
                    cancelPolling(futureHolder[0]);
                    return;
                }
                // 部分失败终态——从 DB 读 resultData 补发 partial_failed 后关闭连接
                if ("PARTIAL_FAILED".equals(snapshotStatus))
                {
                    List<Long> chainChildTaskIds = resolveChainChildTaskIds(snapshot);
                    String chainChildTaskType = (String) snapshot.get("chainChildTaskType");
                    AidExtractTask task = findOwnTaskForStream(taskId, null);
                    trySendPartialFailed(emitter, taskId, task != null ? task.getResultData() : null,
                            chainChildTaskIds, chainChildTaskType);
                    cancelPolling(futureHolder[0]);
                    return;
                }

                // 非终态：推进度事件
                // 同 JVM 去重：若本地 emitter 已直推过此快照，Redis 轮询跳过，避免前端收到重复 progress
                if (updateMillis != null && updateMillis <= sseManager.getLastLocalPushMillis(taskId))
                {
                    // 本地 emitter 已推过，连接是活跃的，更新写出时间防止不必要的心跳
                    lastWriteMillis[0] = System.currentTimeMillis();
                    return;
                }
                // QUEUED 发 queued 事件，其余非终态发 progress（与本地直推事件名统一）
                // 兜底补 taskId：旧快照或漏网写入路径可能没有 taskId，统一 putIfAbsent 保证契约"所有事件必带 taskId"
                snapshot.remove("@type"); // 剥离 Fastjson WriteClassName 写入的类型元字段，避免泄漏到前端 SSE payload
                snapshot.putIfAbsent("taskId", taskId);
                String pollEventName = "QUEUED".equals(snapshotStatus) ? "queued" : "progress";
                emitter.send(SseEmitter.event()
                        .name(pollEventName)
                        .data(normalizePayload(snapshot), MediaType.APPLICATION_JSON));
                // 记录本次成功写出时间，避免心跳重复触发
                lastWriteMillis[0] = System.currentTimeMillis();
            }
            catch (SseHeartbeatClosedException hbe)
            {
                // 心跳失败 = 客户端主动断开 / 浏览器关闭 / 网络中断，属于正常连接收尾
                logger.info("SSE心跳发送失败，连接已关闭: taskId={}", taskId);
                cancelPolling(futureHolder[0]);
                sseManager.unregister(taskId, emitter);
            }
            catch (Exception e)
            {
                // 真正的 Redis 读取异常 / 进度推送异常 / 服务端错误
                logger.warn("SSE Redis轮询推送异常, 停止轮询: taskId={}", taskId, e);
                cancelPolling(futureHolder[0]);
                sseManager.unregister(taskId, emitter);
            }
        }, REDIS_POLL_INTERVAL_MS, REDIS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        futureHolder[0] = future;

        // emitter 生命周期结束时统一清理：取消轮询 + 反注册 emitters map
        // 注意：SseEmitter 的 onCompletion/onTimeout/onError 是单值赋值（覆盖），
        // 因此 register() 不再设置回调，由本处统一负责清理。
        // 用值比对的 unregister(taskId, emitter)，避免前端刷新重连时，
        // 旧连接的延迟 cleanup 误删新连接刚注册的 emitter（导致新连接本地推送失效）。
        Runnable cleanup = () -> {
            cancelPolling(future);
            sseManager.unregister(taskId, emitter);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
    }

    /**
     * 在轮询周期内若距离上次写出超过 {@link #SSE_HEARTBEAT_INTERVAL_SECONDS} 秒，
     * 主动推一个 SSE 注释帧（`: heartbeat\n\n`）防止反代/防火墙关闭 idle 连接。
     * 注释帧不会触发前端的 onmessage / 命名事件回调，对前端业务透明。
     * 推送失败时抛出 {@link SseHeartbeatClosedException}，由 {@link #startRedisPolling} 的外层 catch
     * 按"连接已关闭"处理（info 日志 + cancelPolling + unregister），与真正的 Redis 轮询异常区分开。
     */
    private void maybeSendHeartbeat(SseEmitter emitter, Long taskId, long[] lastWriteMillis)
    {
        long now = System.currentTimeMillis();
        if (now - lastWriteMillis[0] < SSE_HEARTBEAT_INTERVAL_SECONDS * 1000L)
        {
            return;
        }
        try
        {
            emitter.send(SseEmitter.event().comment("heartbeat"));
            lastWriteMillis[0] = now;
        }
        catch (Exception e)
        {
            // 心跳失败通常是客户端断开，抛专用异常让外层按正常收尾处理，不混淆为 Redis 轮询异常
            throw new SseHeartbeatClosedException(e);
        }
    }

    /** 安全取消轮询任务 */
    private void cancelPolling(ScheduledFuture<?> future)
    {
        if (future != null && !future.isCancelled())
        {
            future.cancel(false);
        }
    }

    /**
     * 从 Redis 快照中解析 updateMillis（毫秒时间戳），用于去重。
     * 无法解析时返回 null，由调用方走 fallback 签名去重逻辑。
     */
    private Long resolveUpdateMillis(Map<String, Object> snapshot)
    {
        Object val = snapshot.get("updateMillis");
        if (val instanceof Number)
        {
            return ((Number) val).longValue();
        }
        if (val != null)
        {
            try
            {
                return Long.parseLong(String.valueOf(val));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return null;
    }

    private List<Long> resolveChainChildTaskIds(Map<String, Object> snapshot)
    {
        List<Long> result = new ArrayList<>();
        if (snapshot == null)
        {
            return result;
        }
        Object listVal = snapshot.get("chainChildTaskIds");
        if (listVal instanceof List<?> values)
        {
            for (Object item : values)
            {
                addChainChildTaskId(result, item);
            }
        }
        addChainChildTaskId(result, snapshot.get("chainChildTaskId"));
        return result;
    }

    private static List<Long> singletonTaskId(Long taskId)
    {
        List<Long> result = new ArrayList<>();
        if (Objects.nonNull(taskId) && taskId > 0L)
        {
            result.add(taskId);
        }
        return result;
    }

    private static List<Long> normalizeTaskIds(List<Long> taskIds)
    {
        List<Long> result = new ArrayList<>();
        if (taskIds == null)
        {
            return result;
        }
        for (Long taskId : taskIds)
        {
            addChainChildTaskId(result, taskId);
        }
        return result;
    }

    private static Long firstTaskId(List<Long> taskIds)
    {
        return taskIds == null || taskIds.isEmpty() ? null : taskIds.get(0);
    }

    private static void addChainChildTaskId(List<Long> target, Object value)
    {
        if (Objects.isNull(value))
        {
            return;
        }
        Long taskId = null;
        if (value instanceof Number n)
        {
            taskId = n.longValue();
        }
        else if (StrUtil.isNotBlank(String.valueOf(value)))
        {
            try { taskId = Long.parseLong(String.valueOf(value)); }
            catch (Exception ignored) { return; }
        }
        if (Objects.nonNull(taskId) && taskId > 0L && !target.contains(taskId))
        {
            target.add(taskId);
        }
    }

    /**
     * 构建快照签名（用于无 updateMillis 时的兜底去重）。
     * 拼接关键字段：status + stage + progress + message + updateTime。
     */
    private String buildSnapshotSignature(Map<String, Object> snapshot)
    {
        return String.valueOf(snapshot.getOrDefault("status", ""))
                + "|" + snapshot.getOrDefault("stage", "")
                + "|" + snapshot.getOrDefault("progress", "")
                + "|" + snapshot.getOrDefault("message", "")
                + "|" + snapshot.getOrDefault("updateTime", "");
    }

    /**
     * 判断异常链是否为"客户端主动断开连接"（Broken pipe / ClientAbortException /
     * AsyncRequestNotUsableException / 连接重置等）。
     * 用于 SSE 写出失败时区分"客户端断开"与"真实服务端错误"，
     * 前者静默 info 收尾，不向已死连接二次写、不冒泡到全局异常处理器刷错误堆栈。
     */
    private boolean isClientAbort(Throwable e)
    {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10)
        {
            String cn = cur.getClass().getName();
            if (cn.contains("ClientAbortException")
                    || cn.contains("AsyncRequestNotUsableException"))
            {
                return true;
            }
            String msg = cur.getMessage();
            if (StrUtil.isNotBlank(msg))
            {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || msg.contains("断开的管道")
                        || lower.contains("connection reset")
                        || lower.contains("connection reset by peer"))
                {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 判断异常链是否为"emitter 已被另一条通道关闭"（IllegalStateException: ResponseBodyEmitter has already completed）。
     * SSE 采用"MQ 推送 + Redis 轮询兜底"双通道，两条线程可能对同一 emitter 抢着发终态事件：
     * 先到的已 complete 关闭连接，后到的再写就抛此异常。该情形属良性竞态，业务已正常收尾，
     * 应静默处理，不打 warn、不降级二次写。
     */
    private boolean isAlreadyCompleted(Throwable e)
    {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10)
        {
            if (cur instanceof IllegalStateException)
            {
                String msg = cur.getMessage();
                // emitter 已完成的典型消息：ResponseBodyEmitter has already completed
                if (StrUtil.isNotBlank(msg) && msg.toLowerCase().contains("already completed"))
                {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /** 静默关闭 emitter（客户端已断开时用，仅 completeWithError 触发清理，不再尝试写数据） */
    private void quietlyComplete(SseEmitter emitter, Throwable e)
    {
        try
        {
            emitter.completeWithError(e);
        }
        catch (Exception ignore)
        {
            // 连接已彻底失效，忽略
        }
    }

    /**
     * 心跳推送失败专用异常（客户端主动断开 / 浏览器关闭 / 网络中断）。
     * 与普通 RuntimeException 区分开，让 {@link #startRedisPolling} 的外层 catch
     * 能识别"正常连接关闭"并以 info 级别记录，而非 warn 级别的"Redis 轮询异常"。
     */
    private static final class SseHeartbeatClosedException extends RuntimeException
    {
        SseHeartbeatClosedException(Throwable cause)
        {
            super(cause);
        }
    }
}
