package com.aid.rps.queue;

import java.util.Objects;

import cn.hutool.core.util.StrUtil;

/**
 * 父任务执行周期匹配策略。
 * 有计费/续跑周期使用非空 dispatchToken 隔离；非计费编排父任务没有 token，
 * 仅在 PROCESSING/FINALIZING 且数据库同样无 token 时允许按租约判活。
 */
public final class TaskCycleLivenessPolicy {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_FINALIZING = "FINALIZING";

    private TaskCycleLivenessPolicy() {
    }

    /** 当前调用是否和数据库中的同一执行周期匹配。 */
    public static boolean matches(String status, String expectedToken, String persistedToken) {
        if (STATUS_PROCESSING.equals(status) || STATUS_FINALIZING.equals(status)) {
            if (StrUtil.isBlank(expectedToken)) {
                return StrUtil.isBlank(persistedToken);
            }
            return Objects.equals(expectedToken, persistedToken);
        }
        return StrUtil.isNotBlank(expectedToken) && Objects.equals(expectedToken, persistedToken);
    }
}
