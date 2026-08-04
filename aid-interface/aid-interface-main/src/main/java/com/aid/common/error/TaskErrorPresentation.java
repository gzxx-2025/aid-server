package com.aid.common.error;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;

import java.util.Objects;

/**
 * 统一生成面向用户的错误文案和业务异常。
 *
 * @author 视觉AID
 */
public final class TaskErrorPresentation
{
    private static final int EXCEPTION_MESSAGE_MAX_LENGTH = 12;
    private static final int DETAIL_MESSAGE_MAX_LENGTH = 2000;
    private static final int GENERIC_PRIORITY = 0;
    private static final int SPECIFIC_PRIORITY = 100;

    private TaskErrorPresentation()
    {
    }

    public static String toUserMessage(String rawMessage, String fallbackMessage)
    {
        return toUserMessage(null, rawMessage, fallbackMessage);
    }

    public static String toUserMessage(String modelCode, String rawMessage, String fallbackMessage)
    {
        TaskErrorResult result = ErrorNormalizer.classify(null, modelCode, -1, rawMessage);
        if (isGeneric(result))
        {
            return StrUtil.blankToDefault(fallbackMessage, "任务执行异常");
        }
        return result.getUserMessage();
    }

    public static ServiceException toServiceException(String rawMessage, String fallbackMessage)
    {
        TaskErrorResult result = ErrorNormalizer.classifyByMessage(rawMessage);
        String message = resolveShortMessage(result, fallbackMessage);
        ServiceException exception = new ServiceException(message);
        if (StrUtil.isNotBlank(rawMessage))
        {
            exception.setDetailMessage(StrUtil.sub(rawMessage, 0, DETAIL_MESSAGE_MAX_LENGTH));
        }
        return exception;
    }

    /**
     * 从异常构建用户提示，保留统一计费模块写入的错误码标记。
     */
    public static ServiceException fromThrowable(Throwable throwable, String fallbackMessage)
    {
        TaskErrorResult result = ErrorNormalizer.normalize(throwable);
        String message = resolveShortMessage(result, fallbackMessage);
        ServiceException exception = new ServiceException(message);
        String rawMessage = Objects.isNull(result) ? null : result.getRawMessage();
        if (Objects.nonNull(result) && TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name()
                .equals(result.getErrorCode()))
        {
            // 继续携带内部标记，避免异常再次经过异步包装后丢失用户余额语义。
            exception.setDetailMessage(TaskErrorCode.USER_BALANCE_NOT_ENOUGH.name());
        }
        else if (StrUtil.isNotBlank(rawMessage))
        {
            exception.setDetailMessage(StrUtil.sub(rawMessage, 0, DETAIL_MESSAGE_MAX_LENGTH));
        }
        return exception;
    }

    public static int specificity(TaskErrorResult result)
    {
        return isGeneric(result) ? GENERIC_PRIORITY : SPECIFIC_PRIORITY;
    }

    public static boolean isGeneric(TaskErrorResult result)
    {
        if (Objects.isNull(result) || StrUtil.isBlank(result.getErrorCode()))
        {
            return true;
        }
        return TaskErrorCode.AI_GENERATION_FAILED.name().equals(result.getErrorCode())
                || TaskErrorCode.UNKNOWN.name().equals(result.getErrorCode());
    }

    private static String resolveShortMessage(TaskErrorResult result, String fallbackMessage)
    {
        String message = switch (StrUtil.blankToDefault(result.getErrorCode(), ""))
        {
            case "USER_BALANCE_NOT_ENOUGH" -> "余额不足";
            case "USER_INPUT_INVALID" -> "生成设置有误，请调整";
            case "USER_CONTENT_VIOLATION" -> "内容需调整后重试";
            case "USER_FILE_FORMAT_INVALID" -> "文件格式不支持";
            case "USER_FILE_TOO_LARGE" -> "文件过大，请压缩";
            case "USER_IMAGE_RESOLUTION_INVALID" -> "图片尺寸不支持";
            case "USER_IMAGE_QUALITY_INVALID" -> "图片不清晰，请更换";
            case "USER_AUDIO_INVALID" -> "音频无法处理，请更换";
            case "USER_VIDEO_INVALID" -> "视频无法处理，请更换";
            case "USER_INPUT_EMPTY" -> "请补充生成内容";
            case "USER_INPUT_TOO_LONG" -> "内容过长，请精简";
            case "MERCHANT_QUOTA_EXHAUSTED", "PROVIDER_FREE_TIER_EXHAUSTED",
                    "PROVIDER_QUOTA_EXHAUSTED" -> "模型额度不足";
            case "UPSTREAM_AUTH_INVALID", "UPSTREAM_SERVICE_NOT_OPEN",
                    "MODEL_ACCOUNT_UNAVAILABLE" -> "当前生成服务暂不可用";
            case "PROVIDER_BUSY", "UPSTREAM_RATE_LIMITED" -> "任务较多，稍后重试";
            case "UPSTREAM_TIMEOUT" -> "生成超时，重新生成";
            case "UPSTREAM_NETWORK_ERROR", "UPSTREAM_SERVER_ERROR" -> "生成未完成，稍后重试";
            case "UPSTREAM_BAD_REQUEST" -> "本次生成未完成";
            case "MODEL_PARAMETER_INCOMPATIBLE" -> "设置不支持，调整后重试";
            case "UPSTREAM_CONTENT_FILTERED" -> "内容审核未通过";
            case "REAL_PERSON_RESTRICTED" -> "更换参考图后重试";
            case "USER_FILE_DOWNLOAD_FAILED" -> "重新上传文件后重试";
            case "RESULT_INVALID", "RESULT_FORMAT_INVALID" -> "结果不可用，重新生成";
            case "OSS_PERSIST_FAILED" -> "结果保存失败，重新生成";
            case "PERSIST_FAILED" -> "任务保存失败，重新提交";
            case "TASK_INTERRUPTED" -> "任务已中断，重新发起";
            default -> StrUtil.blankToDefault(fallbackMessage, "任务执行异常");
        };
        return StrUtil.sub(message, 0, EXCEPTION_MESSAGE_MAX_LENGTH);
    }
}
