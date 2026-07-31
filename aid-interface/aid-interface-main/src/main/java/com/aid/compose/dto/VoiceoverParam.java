package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口1 配音参数（与现有 generateAudio 对齐）。
 *
 * @author 视觉AID
 */
@Data
public class VoiceoverParam {

    /** TTS 模型(豆包/minimax) aid_ai_model.id */
    private Long voiceModelId;

    /** 音色库ID */
    private Long voiceLibraryId;

    /** 厂商音色编码 */
    private String timbreCode;

    /** 各段配音文本，下标与 storyboardIds 对齐 */
    private List<String> ttsTexts;

    /**
     * 逐段音色库ID，下标与 storyboardIds 对齐（批量配音按角色绑定音色使用）。
     * 某段非空 = 该段用指定音色（反查模型+音色编码）；为 null = 该段用上方默认音色。
     * 整个字段可空 = 全部段用默认音色（与老入参完全兼容）。
     */
    private List<Long> voiceLibraryIds;
}
