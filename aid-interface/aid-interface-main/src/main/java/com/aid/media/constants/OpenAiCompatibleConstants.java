package com.aid.media.constants;

/**
 * OpenAI 兼容文本协议常量：所有走 Chat Completions 协议的文本厂商统一使用本协议标识。
 *
 * @author 视觉AID
 */
public final class OpenAiCompatibleConstants {

    private OpenAiCompatibleConstants() {
    }

    /** 协议标识：与 aid_ai_model.protocol、aid_media_task.protocol 库表值一致 */
    public static final String PROTOCOL_TEXT = "openai-compatible-text";

    /** 默认模型 ID（仅作占位，生产应在 aid_ai_model 配置 model_code） */
    public static final String DEFAULT_TEXT_MODEL = "gpt-4o-mini";

    /** 用户可见错误文案 */
    public static final String ERROR_API_KEY_EMPTY = "模型密钥未配置";

    /** 用户可见错误文案：base_url 缺失 */
    public static final String ERROR_BASE_URL_EMPTY = "模型网关未配置";

    /** 用户可见错误文案：api_suffix 缺失 */
    public static final String ERROR_API_SUFFIX_EMPTY = "模型路径未配置";
}
