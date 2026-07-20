package com.aid.rps.voice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询角色音色绑定请求 DTO。
 * 对应接口：{@code POST /api/user/asset/rps/voice/query}。
 * 返回单个角色的活跃音色绑定；未绑定则返回 null。
 *
 * @author 视觉AID
 */
@Data
public class RoleVoiceQueryRequest
{
    /** 角色ID（aid_role_prop_scene.id） */
    @NotNull(message = "角色不能空")
    private Long assetId;
}
