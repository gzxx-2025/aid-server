package com.aid.common.utils.image;

/**
 * 图片 URL 校验结果。
 *
 * @author 视觉AID
 */
public final class ImageUrlValidationResult
{
    /** 校验是否通过（等价于 {@link #code} 为 {@link ImageUrlValidationCode#SUCCESS}） */
    private final boolean valid;
    /** 结构化失败原因；成功时为 {@link ImageUrlValidationCode#SUCCESS} */
    private final ImageUrlValidationCode code;
    /** HTTP 状态码；未发生远程请求时为 null */
    private final Integer httpStatus;
    /** 响应头 Content-Type；未拿到 / 无此头时为 null */
    private final String contentType;
    /** 是否走过 GET 降级（HEAD 不支持时由工具类自动降级） */
    private final boolean fallbackGet;

    private ImageUrlValidationResult(boolean valid,
                                     ImageUrlValidationCode code,
                                     Integer httpStatus,
                                     String contentType,
                                     boolean fallbackGet)
    {
        this.valid = valid;
        this.code = code;
        this.httpStatus = httpStatus;
        this.contentType = contentType;
        this.fallbackGet = fallbackGet;
    }

    /** 成功：带 HTTP 状态码 + Content-Type */
    public static ImageUrlValidationResult ok(Integer httpStatus, String contentType, boolean fallbackGet)
    {
        return new ImageUrlValidationResult(true, ImageUrlValidationCode.SUCCESS,
                httpStatus, contentType, fallbackGet);
    }

    /** 失败：只记失败原因（入参 / URL 解析阶段，未发生远程请求） */
    public static ImageUrlValidationResult fail(ImageUrlValidationCode code)
    {
        return new ImageUrlValidationResult(false, code, null, null, false);
    }

    /** 失败：带远程请求上下文（状态码 + Content-Type + 是否走了 GET 降级） */
    public static ImageUrlValidationResult fail(ImageUrlValidationCode code,
                                                Integer httpStatus,
                                                String contentType,
                                                boolean fallbackGet)
    {
        return new ImageUrlValidationResult(false, code, httpStatus, contentType, fallbackGet);
    }

    public boolean isValid()
    {
        return valid;
    }

    public ImageUrlValidationCode getCode()
    {
        return code;
    }

    public Integer getHttpStatus()
    {
        return httpStatus;
    }

    public String getContentType()
    {
        return contentType;
    }

    public boolean isFallbackGet()
    {
        return fallbackGet;
    }

    @Override
    public String toString()
    {
        return "ImageUrlValidationResult{"
                + "valid=" + valid
                + ", code=" + (code != null ? code.getCode() : null)
                + ", httpStatus=" + httpStatus
                + ", contentType='" + contentType + '\''
                + ", fallbackGet=" + fallbackGet
                + '}';
    }
}
