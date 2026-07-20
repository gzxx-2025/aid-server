package com.aid.voice.vo;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * C 端一次性打包的筛选项字典 VO
 * 响应 data 固定包含 5 个键：
 * {@code characterTypes / voiceStyles / toneTags / emotionTags / enums}。
 *
 * @author 视觉AID
 */
@Data
public class VoiceTagBundleVO
{
    /** 角色类型候选（来自 aid_ai_voice_tag，tag_type=character_type） */
    private List<VoiceTagItemVO> characterTypes;

    /** 使用场景候选（来自 aid_ai_voice_tag，tag_type=voice_style） */
    private List<VoiceTagItemVO> voiceStyles;

    /** 音调候选（来自 aid_ai_voice_tag，tag_type=tone） */
    private List<VoiceTagItemVO> toneTags;

    /** 情感候选（以供应商声明为标准：聚合启用音频模型 capability_json.emotions 并集，编码为供应商原生值） */
    private List<VoiceTagItemVO> emotionTags;

    /** 基础枚举：language / gender / age_range */
    private Map<String, List<VoiceEnumItemVO>> enums;
}
