package com.aid.rps.voice.service;

import java.util.Collection;
import java.util.Map;

import com.aid.rps.voice.dto.RoleVoiceBindRequest;
import com.aid.rps.voice.vo.RoleVoiceBindingVO;

/**
 * 角色音色绑定 业务 Service。
 * 承载"给角色挑音色"的全部业务语义：参数校验、音色库反查、冗余字段回写、
 * 批量列表拼装等。基础 CRUD 委托 {@code IAidRoleVoiceBindingService}。
 *
 * @author 视觉AID
 */
public interface IRoleVoiceBindingBusinessService
{
    /**
     * 绑定 / 更换角色音色。
     *
     * @param request 请求入参（已经过 {@code @Valid} 基础非空校验）
     * @param userId  当前用户ID
     * @return 绑定后的 VO
     */
    RoleVoiceBindingVO bindVoice(RoleVoiceBindRequest request, Long userId);

    /**
     * 解除角色音色绑定（软删）。幂等：未绑定也返回成功。
     *
     * @param assetId 角色ID
     * @param userId  当前用户ID
     */
    void unbindVoice(Long assetId, Long userId);

    /**
     * 查询单个角色的活跃音色绑定；未绑定返回 null。
     *
     * @param assetId 角色ID
     * @param userId  当前用户ID
     * @return 绑定 VO 或 null
     */
    RoleVoiceBindingVO queryByAssetId(Long assetId, Long userId);

    /**
     * 批量查询角色 → 音色绑定 VO，供列表接口一次性拼装使用。
     *
     * @param assetIds 角色ID集合；null / 空返回空 map
     * @param userId   当前用户ID
     * @return assetId → VO 映射；无绑定角色不出现在 map
     */
    Map<Long, RoleVoiceBindingVO> queryByAssetIds(Collection<Long> assetIds, Long userId);
}
