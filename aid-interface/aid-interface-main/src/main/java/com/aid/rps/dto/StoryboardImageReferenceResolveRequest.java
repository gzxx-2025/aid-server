package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 解析分镜 image_prompt 中 {@code @图片N[name]} 占位的请求 DTO。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImageReferenceResolveRequest
{
    /** 项目 ID（防越权） */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集 ID（电影项目固定传 0；剧集项目传对应集 ID） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 待解析的分镜 image_prompt 文本（可含 0..N 个 @图片N[name] 占位，空则返回空结果） */
    private String imagePrompt;
}
