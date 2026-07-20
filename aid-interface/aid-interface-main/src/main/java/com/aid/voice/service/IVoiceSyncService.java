package com.aid.voice.service;

import com.aid.voice.vo.RemoteVoiceFetchResultVO;
import com.aid.voice.vo.VoiceSyncResultVO;

/**
 * 音色远程同步 Service。
 *
 * @author 视觉AID
 */
public interface IVoiceSyncService
{
    /**
     * 按模型远程同步音色到 {@code aid_ai_voice_library}。
     *
     * @param modelId  {@code aid_ai_model.id}，必须是 {@code model_type='audio'} 的可用模型
     * @param operator 操作者（用于 create_by / update_by 审计字段）
     * @return 同步结果统计（新增 / 更新 / 软删 / 总数）
     */
    VoiceSyncResultVO syncByModel(Long modelId, String operator);

    /**
     * 拉取远程音色列表 + 标记本地已入库状态（不入库，仅供前端展示选择）。
     */
    RemoteVoiceFetchResultVO fetchRemoteWithLocalStatus(Long modelId);

    /**
     * 按用户选择同步音色：selectedVoiceCodes 入库，removedVoiceCodes 软删。
     */
    VoiceSyncResultVO applySelectedSync(com.aid.voice.dto.VoiceSyncApplyRequest request, String operator);

    /**
     * 清除过期音色：offline_time ≤ NOW() 且 del_flag='0' 的音色批量软删。
     *
     * @return 清除数量
     */
    int cleanExpiredVoices(String operator);
}
