package com.aid.rps.voice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 解除角色音色绑定请求 DTO。
 * 对应接口：{@code POST /api/user/asset/rps/voice/unbind}。
 * 软删现存活跃绑定行；角色没绑定时直接返回成功（幂等）。
 *
 * @author 视觉AID
 */
@Data
public class RoleVoiceUnbindRequest
{
    /** 角色ID（aid_role_prop_scene.id） */
    @NotNull(message = "角色不能空")
    private Long assetId;
}
