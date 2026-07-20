package com.aid.script.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户剧本查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserScriptQueryRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 集数ID(电影传0) */
    private Long episodeId;

    /** 状态(0草稿 1使用 2历史版本)，不传则查全部 */
    private Integer status;
}
