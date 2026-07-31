package com.aid.model.dto;

import lombok.Data;

/**
 * AI模型列表查询请求DTO
 * 供C端用户查询可选的AI模型列表，支持按模型类型 / 生成模式筛选。
 * 所有字段均为可选，不传则返回所有可用模型。
 *
 * @author 视觉AID
 */
@Data
public class AiModelListRequest
{
    /** 模型类型（可选）: text-文本, image-生图, video-生视频, audio-配音 */
    private String modelType;

    /**
     * 生成模式（可选，对 modelType 大类做进一步细分）：
     * text / text_to_image / image_to_image / image_edit / image_upscale /
     * text_to_video / image_to_video / video_to_video / audio
     */
    private String generateMode;
}
