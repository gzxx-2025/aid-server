package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 参考音频列表查询请求。
 * 参考音频按项目隔离，projectId 必传。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioListRequest {

    /** 分页页码，从 1 起，默认 1 */
    private Integer pageNum;

    /** 分页条数，范围 1..100，默认 10 */
    private Integer pageSize;

    /** 所属项目ID */
    @NotNull(message = "项目不能空")
    private Long projectId;

    /** 所属剧集ID；电影项目可不传，剧集项目必传 */
    private Long episodeId;

    /** 音频名称模糊关键字（可选） */
    private String audioName;
}
