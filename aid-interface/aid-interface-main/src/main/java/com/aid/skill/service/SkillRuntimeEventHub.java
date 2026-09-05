package com.aid.skill.service;

import com.aid.skill.vo.SkillInvocationVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Redis 跨节点实时扇出；持久事件表仍是断线和重启后的恢复事实源。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillRuntimeEventHub {
    private static final String CHANNEL_PREFIX = "aid:skill:runtime:event:";
    private static final String SOURCE_NODE_FIELD = "_sourceNodeId";
    private static final int LOCAL_QUEUE_CAPACITY = 256;
    private static final int DISPATCH_THREADS = Math.max(4,
            Math.min(32, Runtime.getRuntime().availableProcessors() * 2));
    private static final ExecutorService LOCAL_DISPATCH_EXECUTOR = Executors.newFixedThreadPool(
            DISPATCH_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "skill-runtime-local-dispatch");
                thread.setDaemon(true);
                return thread;
            });
    private static final ScheduledExecutorService LISTENER_RETRY_EXECUTOR = Executors.newScheduledThreadPool(
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "skill-runtime-listener-retry");
                thread.setDaemon(true);
                return thread;
            });

    private final RedissonClient redissonClient;
    private final String sourceNodeId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<Long, RunState> runStates = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(Long runId, Consumer<SkillInvocationVO.EventView> consumer) {
        AtomicReference<RunState> selected = new AtomicReference<>();
        runStates.compute(runId, (ignored, current) -> {
            RunState state = current == null ? new RunState() : current;
            synchronized (state.subscriptionLock) {
                state.subscribers.add(consumer);
            }
            selected.set(state);
            return state;
        });
        RunState state = selected.get();
        ensureListener(runId, state);
        return () -> unsubscribe(runId, state, consumer);
    }

    public void publish(Long runId, SkillInvocationVO.EventView event) {
        enqueueLocal(runId, event);
        if (event == null) {
            return;
        }
        try {
            JSONObject message = JSONObject.from(event);
            message.put(SOURCE_NODE_FIELD, sourceNodeId);
            redissonClient.getTopic(channel(runId)).publish(message.toJSONString());
        } catch (RuntimeException error) {
            log.warn("Skill Runtime实时事件发布失败, runId={}, eventType={}, errorType={}", runId,
                    event.getEventType(), error.getClass().getSimpleName());
        }
    }

    private void enqueueLocal(Long runId, SkillInvocationVO.EventView event) {
        RunState state = runStates.get(runId);
        if (state == null || event == null || state.subscribers.isEmpty()) {
            return;
        }
        boolean interrupted = false;
        boolean droppedDelta = false;
        synchronized (state.queueLock) {
            while (state.events.size() >= LOCAL_QUEUE_CAPACITY) {
                if (removeOldestDelta(state.events)) {
                    state.gapPending.set(true);
                    break;
                }
                if (isTransientDelta(event)) {
                    state.gapPending.set(true);
                    droppedDelta = true;
                    break;
                }
                try {
                    // 持久事件已落库，但实时投递也不能因队列压力直接丢弃。
                    state.queueLock.wait(1000L);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
                if (state.subscribers.isEmpty()) {
                    return;
                }
            }
            if (!droppedDelta) {
                state.events.addLast(event);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        scheduleDrain(runId, state);
    }

    private boolean removeOldestDelta(ArrayDeque<SkillInvocationVO.EventView> events) {
        Iterator<SkillInvocationVO.EventView> iterator = events.iterator();
        while (iterator.hasNext()) {
            if (isTransientDelta(iterator.next())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private boolean isTransientDelta(SkillInvocationVO.EventView event) {
        return event != null && event.getSeq() == null && "output_delta".equals(event.getEventType());
    }

    private void scheduleDrain(Long runId, RunState state) {
        if (state.draining.compareAndSet(false, true)) {
            LOCAL_DISPATCH_EXECUTOR.execute(() -> drain(runId, state));
        }
    }

    private void drain(Long runId, RunState state) {
        while (true) {
            SkillInvocationVO.EventView event;
            synchronized (state.queueLock) {
                if (state.gapPending.compareAndSet(true, false)) {
                    // 缺口通知与队列出队必须在同一临界区内决策，确保客户端不会先看到缺口后的增量。
                    event = SkillInvocationVO.EventView.builder()
                            .eventType("reconnect_required").createTime(new Date()).build();
                } else {
                    event = state.events.pollFirst();
                    state.queueLock.notifyAll();
                }
                if (event == null) {
                    state.draining.set(false);
                }
            }
            if (event == null) {
                cleanupState(runId, state);
                return;
            }
            dispatch(runId, state, event);
        }
    }

    private void dispatch(Long runId, RunState state, SkillInvocationVO.EventView event) {
        for (Consumer<SkillInvocationVO.EventView> subscriber : state.subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
                unsubscribe(runId, state, subscriber);
            }
        }
    }

    private void ensureListener(Long runId, RunState state) {
        if (runStates.get(runId) != state || state.subscribers.isEmpty()) {
            cleanupState(runId, state);
            return;
        }
        if (state.removalPending.get()) {
            removeListener(runId, state);
            return;
        }
        if (state.listenerId.get() != null || !state.registering.compareAndSet(false, true)) {
            return;
        }
        boolean registered = false;
        try {
            long generation = state.listenerGeneration.incrementAndGet();
            int listenerId = redissonClient.getTopic(channel(runId))
                    .addListener(String.class,
                            (channel, message) -> receiveRemote(runId, state, generation, message));
            registered = state.listenerId.compareAndSet(null, listenerId);
            if (!registered) {
                removeExtraListener(runId, listenerId);
            }
        } catch (RuntimeException error) {
            log.warn("Skill Runtime实时事件订阅失败, runId={}, errorType={}", runId,
                    error.getClass().getSimpleName());
        } finally {
            state.registering.set(false);
        }
        if (registered) {
            resetRetry(state);
            if (state.subscribers.isEmpty() || runStates.get(runId) != state) {
                state.removalPending.set(true);
                removeListener(runId, state);
            }
            return;
        }
        scheduleListenerRetry(runId, state);
    }

    private void removeListener(Long runId, RunState state) {
        Integer listenerId = state.listenerId.get();
        if (listenerId == null) {
            state.removalPending.set(false);
            if (!state.subscribers.isEmpty() && runStates.get(runId) == state) {
                ensureListener(runId, state);
            } else {
                cleanupState(runId, state);
            }
            return;
        }
        if (!state.removing.compareAndSet(false, true)) {
            return;
        }
        state.listenerGeneration.incrementAndGet();
        boolean removed = false;
        try {
            redissonClient.getTopic(channel(runId)).removeListener(listenerId);
            removed = state.listenerId.compareAndSet(listenerId, null);
        } catch (RuntimeException error) {
            log.warn("Skill Runtime实时事件取消订阅失败, runId={}, errorType={}", runId,
                    error.getClass().getSimpleName());
        } finally {
            state.removing.set(false);
        }
        if (removed) {
            state.removalPending.set(false);
            resetRetry(state);
            if (!state.subscribers.isEmpty() && runStates.get(runId) == state) {
                ensureListener(runId, state);
            } else {
                cleanupState(runId, state);
            }
            return;
        }
        scheduleListenerRetry(runId, state);
    }

    private void removeExtraListener(Long runId, int listenerId) {
        try {
            redissonClient.getTopic(channel(runId)).removeListener(listenerId);
        } catch (RuntimeException error) {
            log.warn("Skill Runtime重复订阅清理失败, runId={}, listenerId={}, errorType={}", runId,
                    listenerId, error.getClass().getSimpleName());
        }
    }

    private void scheduleListenerRetry(Long runId, RunState state) {
        if (runStates.get(runId) != state) {
            return;
        }
        ScheduledFuture<?> current = state.retry.get();
        if (current != null && !current.isDone()) {
            return;
        }
        int attempt = state.retryAttempts.incrementAndGet();
        long delaySeconds = Math.min(30L, 1L << Math.min(5, Math.max(0, attempt - 1)));
        AtomicReference<ScheduledFuture<?>> created = new AtomicReference<>();
        ScheduledFuture<?> scheduled = LISTENER_RETRY_EXECUTOR.schedule(
                () -> retryListener(runId, state, created.get()), delaySeconds, TimeUnit.SECONDS);
        created.set(scheduled);
        if (!state.retry.compareAndSet(current, scheduled)) {
            scheduled.cancel(false);
        }
    }

    private void retryListener(Long runId, RunState state, ScheduledFuture<?> scheduled) {
        if (!state.retry.compareAndSet(scheduled, null)) {
            return;
        }
        if (runStates.get(runId) != state) {
            return;
        }
        if (state.removalPending.get() || state.subscribers.isEmpty()) {
            if (state.listenerId.get() != null) {
                state.removalPending.set(true);
                removeListener(runId, state);
            } else {
                cleanupState(runId, state);
            }
            return;
        }
        ensureListener(runId, state);
    }

    private void resetRetry(RunState state) {
        state.retryAttempts.set(0);
        ScheduledFuture<?> retry = state.retry.getAndSet(null);
        if (retry != null) {
            retry.cancel(false);
        }
    }

    private void unsubscribe(Long runId, RunState state,
                             Consumer<SkillInvocationVO.EventView> consumer) {
        boolean shouldRemoveListener = false;
        boolean shouldCleanup = false;
        synchronized (state.subscriptionLock) {
            state.subscribers.remove(consumer);
            if (!state.subscribers.isEmpty()) {
                return;
            }
            synchronized (state.queueLock) {
                state.events.clear();
                state.gapPending.set(false);
                state.queueLock.notifyAll();
            }
            // 与新订阅串行化，避免旧连接取消掉新连接刚安排的 listener retry。
            resetRetry(state);
            if (state.listenerId.get() != null) {
                state.removalPending.set(true);
                shouldRemoveListener = true;
            } else if (!state.registering.get()) {
                shouldCleanup = true;
            }
        }
        if (shouldRemoveListener) {
            removeListener(runId, state);
        } else if (shouldCleanup) {
            cleanupState(runId, state);
        }
    }

    private void cleanupState(Long runId, RunState state) {
        runStates.computeIfPresent(runId, (ignored, current) -> {
            if (current != state || !state.subscribers.isEmpty() || state.listenerId.get() != null
                    || state.registering.get() || state.removing.get() || state.removalPending.get()
                    || state.retry.get() != null || state.draining.get()) {
                return current;
            }
            synchronized (state.queueLock) {
                return state.events.isEmpty() ? null : current;
            }
        });
    }

    private String channel(Long runId) {
        return CHANNEL_PREFIX + runId;
    }

    private SkillInvocationVO.EventView parseEvent(JSONObject value) {
        return SkillInvocationVO.EventView.builder()
                .seq(value.getLong("seq"))
                .eventType(value.getString("eventType"))
                .stage(value.getString("stage"))
                .stepId(value.getLong("stepId"))
                .mediaTaskId(value.getLong("mediaTaskId"))
                .payloadJson(value.getString("payloadJson"))
                .createTime(value.getDate("createTime"))
                .build();
    }

    private void receiveRemote(Long runId, RunState state, long generation, String message) {
        try {
            JSONObject value = JSON.parseObject(message);
            if (value == null || sourceNodeId.equals(value.getString(SOURCE_NODE_FIELD))) {
                return;
            }
            if (runStates.get(runId) == state && state.listenerGeneration.get() == generation
                    && !state.removalPending.get()) {
                enqueueLocal(runId, parseEvent(value));
            }
        } catch (RuntimeException error) {
            log.warn("Skill Runtime实时事件解析失败, runId={}, errorType={}", runId,
                    error.getClass().getSimpleName());
        }
    }

    private static final class RunState {
        private final CopyOnWriteArrayList<Consumer<SkillInvocationVO.EventView>> subscribers =
                new CopyOnWriteArrayList<>();
        private final Object subscriptionLock = new Object();
        private final Object queueLock = new Object();
        private final ArrayDeque<SkillInvocationVO.EventView> events = new ArrayDeque<>();
        private final AtomicBoolean gapPending = new AtomicBoolean(false);
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final AtomicReference<Integer> listenerId = new AtomicReference<>();
        private final AtomicLong listenerGeneration = new AtomicLong(0L);
        private final AtomicBoolean registering = new AtomicBoolean(false);
        private final AtomicBoolean removing = new AtomicBoolean(false);
        private final AtomicBoolean removalPending = new AtomicBoolean(false);
        private final AtomicReference<ScheduledFuture<?>> retry = new AtomicReference<>();
        private final AtomicInteger retryAttempts = new AtomicInteger(0);
    }
}
