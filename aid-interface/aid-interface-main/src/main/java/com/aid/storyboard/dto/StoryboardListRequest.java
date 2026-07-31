package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分镜列表查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class StoryboardListRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID(剧集类型必传，电影类型不传) */
    private Long episodeId;
}
