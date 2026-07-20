package com.aid.rps.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.FormEditChatImageGenerateRequest;

/**
 * 编辑弹窗生图 / 对话作图 Service（0~1 张参考图；0 张走文生图、1 张走单图编辑）。
 *
 * @author 视觉AID
 */
public interface IFormEditChatImageService
{
    /**
     * 发起编辑弹窗生图 / 对话作图任务（异步）。
     *
     * @param request 请求体
     * @param userId  当前用户ID
     * @return 任务VO（taskId + PENDING 状态）
     */
    AssetExtractTaskVO generateEditChatImage(FormEditChatImageGenerateRequest request, Long userId);
}
