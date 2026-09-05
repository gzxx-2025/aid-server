package com.aid.media.service.impl;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 文本流本机取消控制，负责保存部分正文并主动关闭阻塞响应体。 */
final class TextStreamCancellationControl {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> partialText = new AtomicReference<>("");
    private final AtomicReference<Future<?>> future = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> responseBody = new AtomicReference<>();
    private final AtomicLong lastPersistentCheckNanos = new AtomicLong(0L);

    void attach(Future<?> value) {
        future.set(value);
        if (cancelled.get()) {
            value.cancel(true);
        }
    }

    void attachResponseBody(AutoCloseable value) {
        responseBody.set(value);
        if (cancelled.get()) {
            closeResponseBody();
        }
    }

    synchronized boolean updatePartialIfActive(String value) {
        if (cancelled.get()) {
            return false;
        }
        partialText.set(value == null ? "" : value);
        return true;
    }

    String partialText() {
        return partialText.get();
    }

    boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    boolean shouldCheckPersistentCancellation(long intervalNanos) {
        long now = System.nanoTime();
        long previous = lastPersistentCheckNanos.get();
        return (previous == 0L || now - previous >= intervalNanos)
                && lastPersistentCheckNanos.compareAndSet(previous, now);
    }

    void cancel() {
        cancelAndGetPartial();
    }

    String cancelAndGetPartial() {
        String snapshot;
        synchronized (this) {
            cancelled.set(true);
            snapshot = partialText.get();
        }
        closeResponseBody();
        Future<?> current = future.get();
        if (current != null) {
            current.cancel(true);
        }
        return snapshot;
    }

    private void closeResponseBody() {
        AutoCloseable current = responseBody.getAndSet(null);
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception ignored) {
        }
    }
}
