package com.aid.step.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 手动推进步骤请求DTO。
 *
 * @author 视觉AID
 */
@Data
public class StepAdvanceRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID(剧集类型必传，电影类型不传) */
    private Long episodeId;

    /**
     * 当前完成的步骤(1~7)。
     */
    @NotNull(message = "步骤不能为空")
    private Integer completedStep;
}
