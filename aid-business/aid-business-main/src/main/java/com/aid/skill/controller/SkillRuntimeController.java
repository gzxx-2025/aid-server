package com.aid.skill.controller;

import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.skill.dto.SkillInvocationRequests;
import com.aid.skill.service.ISkillCatalogService;
import com.aid.skill.service.ISkillInvocationService;
import com.aid.skill.service.SkillRuntimeEventHub;
import com.aid.skill.vo.SkillInvocationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 为 Web、Open API 和 CLI 提供统一 Skill Runtime 契约。 */
@RestController
@RequestMapping("/api/user/skill/execution")
@RequiredArgsConstructor
@Tag(name = "Skill Runtime")
public class SkillRuntimeController {
    private static final long SSE_TIMEOUT_MILLIS = 90L * 1000L;
    private static final String SOURCE_HEADER = "X-AID-Invoke-Source";
    private static final int EVENT_POLLER_THREADS = Math.max(4,
            Math.min(32, Runtime.getRuntime().availableProcessors() * 2));
    private static final ScheduledExecutorService EVENT_POLLER = Executors.newScheduledThreadPool(
            EVENT_POLLER_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "skill-runtime-event-poller");
        thread.setDaemon(true);
        return thread;
    });

    private final ISkillInvocationService invocationService;
    private final ISkillCatalogService catalogService;
    private final SkillRuntimeEventHub eventHub;

    @PostMapping("/catalog")
    @Operation(summary = "List callable Skill Runtime entrypoints")
    public AjaxResult catalog() {
        return AjaxResult.success(catalogService.listEntrypoints());
    }

    @PostMapping("/invoke")
    @Operation(summary = "调用入口 Skill", description = "同一主体和幂等键合并进行中的 Web/API/CLI 调用")
    public AjaxResult invoke(@Valid @RequestBody SkillInvocationRequests.InvokeRequest request,
                             @RequestHeader(value = SOURCE_HEADER, defaultValue = "WEB") String invokeSource) {
        return AjaxResult.success(invocationService.invoke(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername(), invokeSource));
    }

    @PostMapping("/input/respond")
    @Operation(summary = "提交动态澄清回答", description = "回答必须绑定 requestId、上下文版本和问题结构摘要")
    public AjaxResult respond(@Valid @RequestBody SkillInvocationRequests.RespondRequest request) {
        return AjaxResult.success(invocationService.respond(request, SecurityUtils.getUserId(),
                SecurityUtils.getUsername()));
    }

    @PostMapping("/run/detail")
    @Operation(summary = "查询 Runtime Run", description = "媒体状态和计费状态从关联 aid_media_task 派生")
    public AjaxResult detail(@Valid @RequestBody SkillInvocationRequests.RunRequest request) {
        return AjaxResult.success(invocationService.getRun(request.getRunId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/run/history")
    @Operation(summary = "分页恢复 Runtime 会话历史", description = "仅返回当前用户、项目、剧集和 Skill 范围内的历史 Run")
    public AjaxResult history(@Valid @RequestBody SkillInvocationRequests.HistoryRequest request) {
        return AjaxResult.success(invocationService.listHistory(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/run/events")
    @Operation(summary = "分页恢复 Runtime 事件",
            description = "afterSeq 为排他游标；阶段、正文增量和模型公开的创作思路增量均按 seq 持久化并可重放")
    public AjaxResult events(@Valid @RequestBody SkillInvocationRequests.EventPageRequest request) {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success(Map.of(
                "data", invocationService.listEvents(request, userId),
                "run", invocationService.getRun(request.getRunId(), userId)));
    }

    @PostMapping(value = "/run/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CryptoIgnore
    @Operation(summary = "订阅 Runtime SSE",
            description = "先重放 afterSeq 后的持久事件，再实时推送带 seq 的 reasoning_delta、output_delta、阶段和终态")
    public ResponseEntity<SseEmitter> stream(
            @Valid @RequestBody SkillInvocationRequests.EventPageRequest request) {
        Long userId = SecurityUtils.getUserId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        RuntimeStream stream = new RuntimeStream(emitter, request.getRunId(),
                Math.max(0L, request.getAfterSeq() == null ? 0L : request.getAfterSeq()));
        emitter.onTimeout(stream::complete);
        emitter.onCompletion(stream::close);
        emitter.onError(ignored -> stream.close());
        try {
            // Buffer before ownership validation and replay; nothing is sent until ownership is confirmed.
            stream.bind(eventHub.subscribe(request.getRunId(), stream::acceptLive));
            invocationService.getRun(request.getRunId(), userId);
            stream.sendNamed("connected", Map.of("runId", request.getRunId(), "afterSeq", stream.lastSeq.get()));
            int replayPageSize = Math.min(200, Math.max(1,
                    request.getPageSize() == null ? 100 : request.getPageSize()));
            replayPersistedEvents(stream, userId, replayPageSize);
            if (!stream.activateLive(() -> replayPersistedEvents(stream, userId, 200))) {
                stream.sendNamed("reconnect_required", Map.of("runId", stream.runId,
                        "afterSeq", stream.lastSeq.get()));
                stream.complete();
            } else {
                SkillInvocationVO snapshot = invocationService.getRun(request.getRunId(), userId);
                stream.sendNamed("snapshot", snapshot);
                stream.enableTerminalClose();
                stream.bindPolling(EVENT_POLLER.scheduleWithFixedDelay(
                        () -> pollPersistedEvents(stream, userId), 2L, 2L, TimeUnit.SECONDS));
                if (isTerminal(snapshot.getStatus()) || stream.terminalSeen.get()) {
                    stream.complete();
                }
            }
        } catch (RuntimeException error) {
            stream.close();
            throw error;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-cache");
        headers.set("X-Accel-Buffering", "no");
        headers.setConnection("keep-alive");
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @PostMapping("/run/cancel")
    @Operation(summary = "取消 Runtime Run", description = "取消关联的现有媒体任务并复用其账务收尾")
    public AjaxResult cancel(@Valid @RequestBody SkillInvocationRequests.RunRequest request) {
        invocationService.cancel(request.getRunId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    private void replayPersistedEvents(RuntimeStream stream, Long userId, int pageSize) {
        while (!stream.completed.get()) {
            SkillInvocationRequests.EventPageRequest replay = new SkillInvocationRequests.EventPageRequest();
            replay.setRunId(stream.runId);
            replay.setAfterSeq(stream.lastSeq.get());
            replay.setPageSize(pageSize);
            List<SkillInvocationVO.EventView> events = invocationService.listEvents(replay, userId);
            if (events.isEmpty()) {
                return;
            }
            events.forEach(stream::send);
            if (events.size() < pageSize) {
                return;
            }
        }
    }

    private void pollPersistedEvents(RuntimeStream stream, Long userId) {
        if (stream.completed.get()) {
            return;
        }
        try {
            SkillInvocationRequests.EventPageRequest request = new SkillInvocationRequests.EventPageRequest();
            request.setRunId(stream.runId);
            request.setAfterSeq(stream.lastSeq.get());
            request.setPageSize(200);
            invocationService.listEvents(request, userId).forEach(stream::send);
            if (stream.completed.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            long previousHeartbeat = stream.lastHeartbeatMillis.get();
            if (now - previousHeartbeat >= 15000L
                    && stream.lastHeartbeatMillis.compareAndSet(previousHeartbeat, now)) {
                SkillInvocationVO snapshot = invocationService.getRun(stream.runId, userId);
                stream.sendNamed("heartbeat", Map.of("runId", stream.runId, "afterSeq", stream.lastSeq.get()));
                if (isTerminal(snapshot.getStatus())) {
                    stream.sendNamed("snapshot", snapshot);
                    stream.complete();
                }
            }
        } catch (RuntimeException error) {
            stream.sendNamed("reconnect_required", Map.of("runId", stream.runId,
                    "afterSeq", stream.lastSeq.get()));
            stream.complete();
        }
    }

    private static final class RuntimeStream {
        private final SseEmitter emitter;
        private final Long runId;
        private final AtomicLong lastSeq;
        private final AtomicLong lastHeartbeatMillis = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicBoolean terminalSeen = new AtomicBoolean(false);
        private final Object sendLock = new Object();
        private final Object liveLock = new Object();
        private final Queue<SkillInvocationVO.EventView> bufferedLiveEvents = new ConcurrentLinkedQueue<>();
        private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> polling = new AtomicReference<>();
        private boolean liveActivated;
        private boolean closeOnTerminal;
        private boolean liveBufferOverflow;
        private int bufferedLiveChars;
        private static final int MAX_BUFFERED_LIVE_EVENTS = 256;
        private static final int MAX_BUFFERED_LIVE_CHARS = 512 * 1024;

        private RuntimeStream(SseEmitter emitter, Long runId, long afterSeq) {
            this.emitter = emitter;
            this.runId = runId;
            this.lastSeq = new AtomicLong(afterSeq);
        }

        private void bind(AutoCloseable closeable) {
            subscription.set(closeable);
            if (completed.get() && subscription.compareAndSet(closeable, null)) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 已完成连接不再保留本地订阅。
                }
            }
        }

        private void bindPolling(ScheduledFuture<?> future) {
            polling.set(future);
            if (completed.get() && polling.compareAndSet(future, null)) {
                future.cancel(false);
            }
        }

        private void send(SkillInvocationVO.EventView event) {
            synchronized (sendLock) {
                if (completed.get()) {
                    return;
                }
                if (event.getSeq() != null && event.getSeq() <= lastSeq.get()) {
                    return;
                }
                if (!sendNamedLocked(event.getEventType(), event)) {
                    return;
                }
                if (event.getSeq() != null) {
                    lastSeq.set(event.getSeq());
                }
                if ("terminal".equals(event.getEventType())) {
                    terminalSeen.set(true);
                    if (closeOnTerminal) {
                        complete();
                    }
                }
            }
        }

        private void acceptLive(SkillInvocationVO.EventView event) {
            synchronized (liveLock) {
                if (completed.get()) {
                    return;
                }
                if (!liveActivated) {
                    int eventChars = event.getPayloadJson() == null ? 0 : event.getPayloadJson().length();
                    if (liveBufferOverflow || bufferedLiveEvents.size() >= MAX_BUFFERED_LIVE_EVENTS
                            || bufferedLiveChars + eventChars > MAX_BUFFERED_LIVE_CHARS) {
                        liveBufferOverflow = true;
                        bufferedLiveEvents.clear();
                        bufferedLiveChars = 0;
                        return;
                    }
                    bufferedLiveEvents.add(event);
                    bufferedLiveChars += eventChars;
                    return;
                }
                sendLive(event);
            }
        }

        private boolean activateLive(Runnable finalCatchUp) {
            synchronized (liveLock) {
                if (completed.get() || liveBufferOverflow) {
                    bufferedLiveEvents.clear();
                    bufferedLiveChars = 0;
                    return false;
                }
                // Holding the same lock as acceptLive makes the database catch-up and live hand-off atomic.
                finalCatchUp.run();
                SkillInvocationVO.EventView event;
                while (!completed.get() && (event = bufferedLiveEvents.poll()) != null) {
                    sendLive(event);
                }
                bufferedLiveChars = 0;
                liveActivated = true;
                return !completed.get();
            }
        }

        private void enableTerminalClose() {
            synchronized (sendLock) {
                closeOnTerminal = true;
            }
        }

        private void sendLive(SkillInvocationVO.EventView event) {
            send(event);
        }

        private void sendNamed(String event, Object data) {
            synchronized (sendLock) {
                sendNamedLocked(event, data);
            }
        }

        private boolean sendNamedLocked(String event, Object data) {
            if (completed.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
                return true;
            } catch (IOException | IllegalStateException error) {
                complete();
                return false;
            }
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                closeSubscription();
                emitter.complete();
            }
        }

        private void close() {
            completed.set(true);
            closeSubscription();
        }

        private void closeSubscription() {
            // ConcurrentLinkedQueue can be cleared without liveLock. Avoid sendLock -> liveLock
            // inversion with acceptLive/activateLive, which intentionally use liveLock -> sendLock.
            bufferedLiveEvents.clear();
            ScheduledFuture<?> future = polling.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            AutoCloseable closeable = subscription.getAndSet(null);
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 本地订阅关闭失败不影响持久事件恢复。
            }
        }
    }
}
