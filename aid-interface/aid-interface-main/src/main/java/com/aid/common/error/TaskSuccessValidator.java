package com.aid.common.error;

import org.apache.commons.lang3.StringUtils;

/**
 * 业务成功校验规则。
 *
 * @author 视觉AID
 */
public final class TaskSuccessValidator {

    private TaskSuccessValidator() {
    }

    /**
     * 校验图片任务是否真正业务成功。
     *
     * @param originUrl 原始 URL
     * @param ossUrl OSS 持久化 URL（可选）
     * @param requireOss 是否强制要求 OSS 就绪（true = OSS 未就绪也算失败）
     * @return null 表示业务成功;非 null 表示应降级为 FAILED,返回对应的错误结果
     */
    public static TaskErrorResult validateImage(String originUrl, String ossUrl, boolean requireOss) {
        if (StringUtils.isBlank(originUrl) && StringUtils.isBlank(ossUrl)) {
            return TaskErrorResult.of(TaskErrorCode.RESULT_INVALID,
                    "SUCCEEDED but no image URL found");
        }
        if (requireOss && StringUtils.isBlank(ossUrl)) {
            return TaskErrorResult.of(TaskErrorCode.OSS_PERSIST_FAILED,
                    "SUCCEEDED but oss URL not ready");
        }
        return null;
    }

    /**
     * 校验视频任务是否真正业务成功。
     */
    public static TaskErrorResult validateVideo(String originUrl, String ossUrl, boolean requireOss) {
        if (StringUtils.isBlank(originUrl) && StringUtils.isBlank(ossUrl)) {
            return TaskErrorResult.of(TaskErrorCode.RESULT_INVALID,
                    "SUCCEEDED but no video URL found");
        }
        if (requireOss && StringUtils.isBlank(ossUrl)) {
            return TaskErrorResult.of(TaskErrorCode.OSS_PERSIST_FAILED,
                    "SUCCEEDED but oss URL not ready");
        }
        return null;
    }

    /**
     * 校验文本任务是否真正业务成功。
     */
    public static TaskErrorResult validateText(String textContent) {
        if (StringUtils.isBlank(textContent)) {
            return TaskErrorResult.of(TaskErrorCode.RESULT_INVALID,
                    "SUCCEEDED but text content is empty");
        }
        return null;
    }
}
