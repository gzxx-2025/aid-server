package com.aid.rps.dto;

import java.util.List;

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

    /** 剧集 ID（兼容保留：解析域为项目级，不再按集过滤；电影项目固定传 0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 待解析的分镜 image_prompt 文本（可含 0..N 个 @图片N[name] 占位，空则返回空结果） */
    private String imagePrompt;

    /** 用户显式选择的参考音频记录 ID（可选，必须属于当前用户与项目）。 */
    private List<Long> referenceAudioRecordIds;

    /** 用户显式选择的上传参考音频 ID（可选，必须属于当前用户与项目）。 */
    private List<Long> referenceAudioIds;
}
