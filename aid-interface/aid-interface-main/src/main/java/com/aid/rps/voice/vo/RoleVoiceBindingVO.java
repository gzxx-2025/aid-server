package com.aid.rps.voice.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 角色音色绑定 VO。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoleVoiceBindingVO
{
    /** 绑定主键（aid_role_voice_binding.id） */
    private Long bindingId;

    /** 角色ID */
    private Long assetId;
    /** 音色ID（aid_ai_voice_library.id） */
    private Long voiceLibraryId;

    /** 音色编码（调 TTS 用） */
    private String voiceCode;
    /** 音色展示名 */
    private String voiceName;

    /** 头像图 URL（出参拼域名） */
    @MediaUrl
    private String avatarUrl;

    /** 试听音频 URL（出参拼域名） */
    @MediaUrl
    private String sampleUrl;

    /** 试听文案 */
    private String sampleText;

    /** 语言区域码 */
    private String language;

    /** 性别 */
    private String gender;

    /** 年龄段 */
    private String ageRange;
    /** 是否支持情感参数 */
    private Boolean supportsEmotion;

    /** 是否支持语速参数 */
    private Boolean supportsSpeed;

    /** 是否支持音调参数 */
    private Boolean supportsPitch;

    /** 默认语速 */
    private BigDecimal defaultSpeed;

    /** 默认音调 */
    private BigDecimal defaultPitch;
    /** 覆盖语速；null 走默认 */
    private BigDecimal overrideSpeed;

    /** 覆盖音调；null 走默认 */
    private BigDecimal overridePitch;

    /** 覆盖情感 */
    private String overrideEmotion;
    /** 音色下架时间（来自 aid_ai_voice_library） */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date offlineTime;

    /** 音色是否已下架（offlineTime 为空或 &le; NOW() 视为已下架） */
    private Boolean offline;
}
