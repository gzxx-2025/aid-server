package com.aid.common.core.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import com.aid.common.constant.HttpStatus;
import com.aid.common.utils.MessageUtils;

/**
 * 操作消息提醒
 *
 * @author 视觉AID
 */
public class AjaxResult extends HashMap<String, Object>
{
    private static final long serialVersionUID = 1L;

    /** 状态码 */
    public static final String CODE_TAG = "code";

    /** 返回内容 */
    public static final String MSG_TAG = "msg";

    /** 数据对象 */
    public static final String DATA_TAG = "data";

    /** 上游额度错误标记：命中后返回统一可操作文案，不暴露账号、金额等明细。 */
    private static final String[] PROVIDER_QUOTA_MESSAGE_TOKENS = {
            "模型余额", "模型额度", "模型免费额度", "服务额度不足",
            "供应商余额", "供应商额度", "服务商余额", "服务商额度",
            "上游账户余额",
            "insufficient credits", "insufficient balance", "quota exhausted", "quota exceeded"
    };

    /** 上游认证和账号错误标记：命中后不得作为 C 端 msg 原样返回。 */
    private static final String[] PROVIDER_INTERNAL_MESSAGE_TOKENS = {
            "上游账号不可用", "上游账户不可用",
            "模型认证失败", "模型鉴权失败", "上游认证失败", "上游鉴权失败",
            "invalid api key", "incorrect api key", "api key not valid"
    };

    /** 上游额度不足的统一 C 端提示。 */
    private static final String PROVIDER_QUOTA_MESSAGE = "模型额度不足，请联系管理员";

    /** C 端统一提示，不暴露平台采购的模型账户、额度和密钥状态。 */
    private static final String PROVIDER_UNAVAILABLE_MESSAGE = "当前生成服务暂不可用";

    /**
     * 初始化一个新创建的 AjaxResult 对象，使其表示一个空消息。
     */
    public AjaxResult()
    {
    }

    /**
     * 初始化一个新创建的 AjaxResult 对象
     *
     * @param code 状态码
     * @param msg 返回内容
     */
    public AjaxResult(int code, String msg)
    {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
    }

    /**
     * 初始化一个新创建的 AjaxResult 对象
     *
     * @param code 状态码
     * @param msg 返回内容
     * @param data 数据对象
     */
    public AjaxResult(int code, String msg, Object data)
    {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
        super.put(DATA_TAG, data);
    }

    /**
     * 返回成功消息
     *
     * @return 成功消息
     */
    public static AjaxResult success()
    {
        return AjaxResult.success(MessageUtils.message("operation.success"));
    }

    /**
     * 返回成功数据
     *
     * @return 成功消息
     */
    public static AjaxResult success(Object data)
    {
        return AjaxResult.success(MessageUtils.message("operation.success"), data);
    }

    /**
     * 返回成功消息
     *
     * @param msg 返回内容
     * @return 成功消息
     */
    public static AjaxResult success(String msg)
    {
        return AjaxResult.success(msg, null);
    }

    /**
     * 返回成功消息
     *
     * @param msg 返回内容
     * @param data 数据对象
     * @return 成功消息
     */
    public static AjaxResult success(String msg, Object data)
    {
        return new AjaxResult(HttpStatus.SUCCESS, msg, data);
    }

    /**
     * 返回警告消息
     *
     * @param msg 返回内容
     * @return 警告消息
     */
    public static AjaxResult warn(String msg)
    {
        return AjaxResult.warn(msg, null);
    }

    /**
     * 返回警告消息
     *
     * @param msg 返回内容
     * @param data 数据对象
     * @return 警告消息
     */
    public static AjaxResult warn(String msg, Object data)
    {
        return new AjaxResult(HttpStatus.WARN, msg, data);
    }

    /**
     * 返回错误消息
     *
     * @return 错误消息
     */
    public static AjaxResult error()
    {
        return AjaxResult.error(MessageUtils.message("operation.failed"));
    }

    /**
     * 返回错误消息
     *
     * @param msg 返回内容
     * @return 错误消息
     */
    public static AjaxResult error(String msg)
    {
        return AjaxResult.error(msg, null);
    }

    /**
     * 返回错误消息
     *
     * @param msg 返回内容
     * @param data 数据对象
     * @return 错误消息
     */
    public static AjaxResult error(String msg, Object data)
    {
        return new AjaxResult(HttpStatus.ERROR, sanitizeErrorMessage(msg), data);
    }

    /**
     * 返回错误消息
     *
     * @param code 状态码
     * @param msg 返回内容
     * @return 错误消息
     */
    public static AjaxResult error(int code, String msg)
    {
        return new AjaxResult(code, sanitizeErrorMessage(msg), null);
    }

    /**
     * 错误响应最后一道展示防线；日志和任务表仍保留原始信息供后台排查。
     */
    private static String sanitizeErrorMessage(String message)
    {
        if (Objects.isNull(message))
        {
            return null;
        }
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        for (String token : PROVIDER_QUOTA_MESSAGE_TOKENS)
        {
            if (lowerMessage.contains(token))
            {
                return PROVIDER_QUOTA_MESSAGE;
            }
        }
        for (String token : PROVIDER_INTERNAL_MESSAGE_TOKENS)
        {
            if (lowerMessage.contains(token))
            {
                return PROVIDER_UNAVAILABLE_MESSAGE;
            }
        }
        return message;
    }

    /**
     * 是否为成功消息
     *
     * @return 结果
     */
    public boolean isSuccess()
    {
        return Objects.equals(HttpStatus.SUCCESS, this.get(CODE_TAG));
    }

    /**
     * 是否为警告消息
     *
     * @return 结果
     */
    public boolean isWarn()
    {
        return Objects.equals(HttpStatus.WARN, this.get(CODE_TAG));
    }

    /**
     * 是否为错误消息
     *
     * @return 结果
     */
    public boolean isError()
    {
        return Objects.equals(HttpStatus.ERROR, this.get(CODE_TAG));
    }

    /**
     * 方便链式调用
     *
     * @param key 键
     * @param value 值
     * @return 数据对象
     */
    @Override
    public AjaxResult put(String key, Object value)
    {
        super.put(key, value);
        return this;
    }
}
