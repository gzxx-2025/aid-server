package com.aid.media.constants;

/**
 * Google Gemini 文本 / 图片模型相关常量：协议标识、网关默认参数、HTTP 头等。
 */
public final class GeminiConstants {

    private GeminiConstants() {
    }
    /** 文本（非流式）：Gemini REST :generateContent，与 aid_media_task.protocol 库表值一致 */
    public static final String PROTOCOL_TEXT = "gemini-text";

    /** 默认思考等级：Gemini 文本默认显式下发 thinkingLevel=low，调用方显式给出时不覆盖；不开启 includeThoughts */
    public static final String DEFAULT_THINKING_LEVEL = "low";

    /** 默认模型 ID（仅作占位，生产应在 aid_ai_model 配置 model_code） */
    public static final String DEFAULT_TEXT_MODEL = "gemini-3.1-pro-preview";
    /** 图片协议标识：与 aid_media_task.protocol、GeminiImageProviderClient.protocol() 一致 */
    public static final String PROTOCOL_IMAGE = "gemini-image";

    /** aid_ai_provider.provider_code 约定：Google Gemini 服务商登记为该 code，调度层按此精确路由 */
    public static final String PROVIDER_CODE = "gemini";

    /** Nano Banana 2（高效率图片模型）—— gemini-3.1-flash-image-preview */
    public static final String MODEL_FLASH_IMAGE = "gemini-3.1-flash-image-preview";

    /** Nano Banana Pro（高质量图片模型）—— gemini-3-pro-image-preview */
    public static final String MODEL_PRO_IMAGE = "gemini-3-pro-image-preview";
    /** 文本弱匹配用子串（小写比较）：模型名含 gemini 关键字时归 Gemini 文本 provider */
    public static final String MODEL_HINT_GEMINI = "gemini";

    /** 鉴权请求头：Gemini 不使用 Bearer，使用专属 header */
    public static final String HEADER_API_KEY = "x-goog-api-key";

    /** REST endpoint suffix 段：base_url + api_suffix + {model}:generateContent（非流式，普通 JSON 响应） */
    public static final String OPERATION_GENERATE_CONTENT = ":generateContent";

    /** 用户可见错误文案 */
    public static final String ERROR_API_KEY_EMPTY = "Gemini apiKey 为空";
    /** Gemini 图片生成默认宽高比 */
    public static final String DEFAULT_ASPECT_RATIO = "1:1";

    /** HTTP 连接/读取超时（分钟）：图片模型生成耗时可能较长 */
    public static final int IMAGE_HTTP_TIMEOUT_MINUTES = 5;

    /** OSS 上传后缀 */
    public static final String IMAGE_SUFFIX_PNG = ".png";

    /** OSS 上传 MIME */
    public static final String IMAGE_CONTENT_TYPE_PNG = "image/png";

    /** 日志截断长度 */
    public static final int LOG_RESPONSE_SNIPPET_MAX = 500;
}
