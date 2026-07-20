package com.aid.common.utils.image;

/**
 * 图片 URL 校验失败原因枚举
 * 给业务层一个稳定的 code 族用于识别失败语义：打日志、转用户短文案、决定重试策略等。
 * {@link #SUCCESS} 表示校验通过。
 *
 * @author 视觉AID
 */
public enum ImageUrlValidationCode
{
    /** 校验通过 */
    SUCCESS("success", "校验通过"),

    /** 入参为空 / 全空白 */
    EMPTY_URL("empty_url", "URL为空"),

    /** URL 不合法（解析失败） */
    INVALID_URL("invalid_url", "URL非法"),

    /** 协议不受支持（仅允许 http / https） */
    UNSUPPORTED_PROTOCOL("unsupported_protocol", "协议不支持"),

    /** host 缺失 */
    MISSING_HOST("missing_host", "host缺失"),

    /** 指向内网 / 回环 / 云 metadata 等私有地址（SSRF 防护） */
    PRIVATE_ADDRESS("private_address", "非法地址"),

    /** 网络连接建立失败 */
    CONNECT_FAILED("connect_failed", "连接失败"),

    /** 请求超时 */
    TIMEOUT("timeout", "请求超时"),

    /** HTTP 状态码异常（非 2xx / 非成功范围） */
    BAD_STATUS("bad_status", "状态码异常"),

    /** 响应头 Content-Type 为空 */
    EMPTY_CONTENT_TYPE("empty_content_type", "类型缺失"),

    /** 响应头 Content-Type 非图片 */
    NOT_IMAGE("not_image", "非图片资源"),

    /** HEAD 不支持且 GET 降级也失败 */
    HEAD_AND_GET_FAILED("head_and_get_failed", "无法校验"),

    /** 其他未分类异常（打日志使用，不直接返给前端） */
    UNKNOWN("unknown", "未知异常");

    private final String code;
    private final String message;

    ImageUrlValidationCode(String code, String message)
    {
        this.code = code;
        this.message = message;
    }

    public String getCode()
    {
        return code;
    }

    public String getMessage()
    {
        return message;
    }
}
