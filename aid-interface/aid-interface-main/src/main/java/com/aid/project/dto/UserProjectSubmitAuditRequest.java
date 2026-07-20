package com.aid.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户项目提交审核请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectSubmitAuditRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;
}
