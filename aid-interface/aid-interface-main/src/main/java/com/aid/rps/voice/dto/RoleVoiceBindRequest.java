package com.aid.rps.voice.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 绑定 / 更换角色音色请求 DTO。
 * 对应接口：{@code POST /api/user/asset/rps/voice/bind}。
 * 支持新建绑定和"同一角色换音色"两种语义——统一走 upsert。
 *
 * @author 视觉AID
 */
@Data
public class RoleVoiceBindRequest
{
    /** 角色ID（aid_role_prop_scene.id，要求 asset_type=character） */
    @NotNull(message = "角色不能空")
    private Long assetId;

    /** 所选音色ID（aid_ai_voice_library.id） */
    @NotNull(message = "音色不能空")
    private Long voiceLibraryId;

    /** 可选：覆盖语速（0.50~2.00），null 走音色默认 */
    private BigDecimal overrideSpeed;

    /** 可选：覆盖音调（-12.00~12.00），null 走音色默认 */
    private BigDecimal overridePitch;

    /** 可选：覆盖情感（供应商原生编码，须命中音色所属模型 capability_json.emotions 白名单） */
    private String overrideEmotion;
}
