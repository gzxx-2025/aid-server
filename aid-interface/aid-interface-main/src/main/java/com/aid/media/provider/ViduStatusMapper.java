package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.constants.ViduConstants;

/**
 * Vidu 任务状态归一化 + 错误码分类的统一映射工具。
 * 视频/图片 Provider 与回调入口共用同一套状态机，避免各处复制 normalizeStatus，
 * 保证「轮询」与「回调」对同一上游响应得到完全一致的归一化结果（幂等收口的前提）。
 */
public final class ViduStatusMapper {

    private ViduStatusMapper() {
    }

    /**
     * 将厂商 state/status 文本归一化为平台三态（PROCESSING/SUCCEEDED/FAILED）。
     * 空状态默认 PROCESSING，避免误判失败。
     */
    public static String normalizeStatus(String status) {
        if (StrUtil.isBlank(status)) {
            return ViduConstants.TASK_STATUS_PROCESSING;
        }
        String upper = status.toUpperCase();
        if (upper.contains(ViduConstants.VENDOR_TOKEN_SUCC) || upper.contains(ViduConstants.VENDOR_TOKEN_COMPLETE)
            || upper.contains(ViduConstants.VENDOR_TOKEN_DONE)) {
            return ViduConstants.TASK_STATUS_SUCCEEDED;
        }
        if (upper.contains(ViduConstants.VENDOR_TOKEN_FAIL) || upper.contains(ViduConstants.VENDOR_TOKEN_ERROR)
            || upper.contains(ViduConstants.VENDOR_TOKEN_CANCEL) || upper.contains(ViduConstants.VENDOR_TOKEN_REJECT)) {
            return ViduConstants.TASK_STATUS_FAILED;
        }
        return ViduConstants.TASK_STATUS_PROCESSING;
    }

    /**
     * 错误码分类纠偏：
     *
     *   - 命中终态错误码集合 → 直接判 FAILED（触发现有失败收口/退款）；
     *   - 命中可重试错误码集合 → 若被误判为 FAILED 则纠正回 PROCESSING，让轮询继续兜底；
     *   - 其余情况保持原归一化状态不变。
     *
     */
    public static String applyErrorCodeClassification(String normalized, String errCode) {
        if (StrUtil.isBlank(errCode)) {
            return normalized;
        }
        if (ViduConstants.TERMINAL_ERROR_CODES.contains(errCode)) {
            return ViduConstants.TASK_STATUS_FAILED;
        }
        if (ViduConstants.RETRYABLE_ERROR_CODES.contains(errCode)
            && ViduConstants.TASK_STATUS_FAILED.equals(normalized)) {
            return ViduConstants.TASK_STATUS_PROCESSING;
        }
        return normalized;
    }
}
