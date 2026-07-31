package com.aid.step.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询步骤状态请求DTO。
 *
 * @author 视觉AID
 */
@Data
public class StepStatusRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID(剧集类型必传，电影类型不传) */
    private Long episodeId;
}
