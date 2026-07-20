package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增分镜请求DTO
 *
 * @author 视觉AID
 */
@Data
public class StoryboardCreateRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID(剧集类型必传，电影类型不传) */
    private Long episodeId;

    /** 分镜标题(不传则自动生成) */
    private String title;

    /**
     * 指定插入位置。
     */
    @jakarta.validation.constraints.Min(value = 0, message = "insertAfterSort 不能为负")
    private Long insertAfterSort;
}
