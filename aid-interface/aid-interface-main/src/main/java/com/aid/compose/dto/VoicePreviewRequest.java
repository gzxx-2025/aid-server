package com.aid.compose.dto;

import lombok.Data;

/**
 * 接口3 入参：试听（同步、免费、不落库）。
 *
 * @author 视觉AID
 */
@Data
public class VoicePreviewRequest {

    /** 试听文本，字数上限 = aid_config 试听秒数 × 4.5 字/秒（缺省 15 字），超过直接拒绝。必填 */
    private String text;

    /** 豆包/minimax TTS 模型 aid_ai_model.id */
    private Long voiceModelId;

    /** 厂商音色编码 */
    private String timbreCode;

    /** 情感编码（可选，供应商原生编码如 happy / sad，须命中模型 capability_json.emotions 白名单） */
    private String emotion;

    /** 情感强度（可选，1~5，缺省 4；仅豆包多情感场景使用，MiniMax 忽略），与 emotion 配合生效 */
    private Integer emotionScale;
}
