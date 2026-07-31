package com.aid.script.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 根据项目ID和剧集ID查询剧本详情请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserScriptDetailByProjectRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 集数ID（电影传0） */
    @NotNull(message = "集数ID不能为空")
    private Long episodeId;
}
