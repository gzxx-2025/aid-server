package com.aid.media.provider;

import com.aid.media.constants.ViduConstants;

import java.util.Locale;
import java.util.Set;

/**
 * Vidu 任务状态的统一映射工具。
 * 视频/图片 Provider 与回调入口共用同一套状态机，避免各处复制 normalizeStatus，
 * 保证「轮询」与「回调」对同一上游响应得到完全一致的归一化结果（幂等收口的前提）。
 */
public final class ViduStatusMapper {

    private static final Set<String> PROCESSING_STATES = Set.of("created", "queueing", "processing");
    private static final String SUCCESS_STATE = "success";
    private static final String FAILED_STATE = "failed";

    private ViduStatusMapper() {
    }

    /**
     * 严格按 Vidu 文档的 state/status 枚举归一化。
     * 未知或空状态保持 PROCESSING，交由调用方标记为查询异常，不猜测终态。
     */
    public static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return ViduConstants.TASK_STATUS_PROCESSING;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        if (SUCCESS_STATE.equals(normalized)) {
            return ViduConstants.TASK_STATUS_SUCCEEDED;
        }
        if (FAILED_STATE.equals(normalized)) {
            return ViduConstants.TASK_STATUS_FAILED;
        }
        return ViduConstants.TASK_STATUS_PROCESSING;
    }

    /** 是否为 Vidu 文档明确列出的状态。 */
    public static boolean isKnownState(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return PROCESSING_STATES.contains(normalized)
            || SUCCESS_STATE.equals(normalized)
            || FAILED_STATE.equals(normalized);
    }

    /** 是否为 Vidu 文档明确列出的非终态。 */
    public static boolean isProcessingState(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return PROCESSING_STATES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    /** 是否为 Vidu 文档明确列出的终态。 */
    public static boolean isTerminalState(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return SUCCESS_STATE.equals(normalized) || FAILED_STATE.equals(normalized);
    }
}
