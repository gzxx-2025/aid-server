package com.aid.common.moderation;

import lombok.Data;

/**
 * 图片审查结果
 *
 * @author 视觉AID
 */
@Data
public class ModerationResult
{
    /**
     * 审查建议：Pass/Review/Block
     */
    private String suggestion;

    /**
     * 命中的一级标签
     */
    private String label;

    /**
     * 命中的二级标签
     */
    private String subLabel;

    /**
     * 命中分数
     */
    private Integer score;

    /**
     * 厂商返回的请求 ID
     */
    private String requestId;

    /**
     * 厂商回传的文件 MD5
     */
    private String fileMd5;

    /**
     * 厂商返回的原始 JSON
     */
    private String rawJson;

    /**
     * 是否审查异常
     */
    private boolean error;

    /**
     * 异常信息（error=true 时填充）
     */
    private String errorMessage;

    /**
     * 厂商返回的错误码（error=true 时填充；用于「图片本身问题」与「系统/鉴权问题」的精细化区分）
     */
    private String errorCode;

    /**
     * 构造一个异常结果
     *
     * @param msg 异常信息
     * @return 审查结果
     */
    public static ModerationResult error(String msg)
    {
        ModerationResult result = new ModerationResult();
        result.setError(true);
        result.setErrorMessage(msg);
        return result;
    }

    /**
     * 构造一个带错误码的异常结果
     *
     * @param code 错误码
     * @param msg  异常信息
     * @return 审查结果
     */
    public static ModerationResult error(String code, String msg)
    {
        ModerationResult result = new ModerationResult();
        result.setError(true);
        result.setErrorCode(code);
        result.setErrorMessage(msg);
        return result;
    }
}
