package com.aid.billing.enums;

/**
 * 统一计费口径枚举：决定金额计算公式的唯一分发依据。
 */
public enum MeterType {

    /** 按 token 用量计费（文本 LLM、Gemini 图片等） */
    TOKEN,

    /** 按单张固定价计费（wan/jimeng 图片等） */
    PER_IMAGE,

    /** 按视频秒数计费（阿里/即梦视频等） */
    PER_SECOND,

    /** 按 SKU 整包价计费（豆包视频等） */
    SKU_PACKAGE,

    /** 按文本字符数计费（TTS 语音合成：minimax / 豆包等） */
    PER_CHAR;

    /**
     * 安全解析：null/空串/不识别 → null。
     */
    public static MeterType of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
