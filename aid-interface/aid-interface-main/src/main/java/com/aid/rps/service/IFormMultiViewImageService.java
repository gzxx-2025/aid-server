package com.aid.rps.service;

import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.FormMultiViewImageGenerateRequest;

/**
 * 多机位形态生图 Service：围绕已有 form 参考图按机位提示词生成只改机位、不改主体的新图。
 *
 * @author 视觉AID
 */
public interface IFormMultiViewImageService
{
    /**
     * 发起多机位形态生图任务（异步）。
     *
     * @param request 多机位请求（formId / imageUrl / anglePrompt / modelCode / 可选 aspectRatio）
     * @param userId  当前用户ID
     * @return 任务VO（taskId + PENDING 状态）
     */
    AssetExtractTaskVO generateMultiViewImage(FormMultiViewImageGenerateRequest request, Long userId);
}
