package com.aid.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户项目删除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectDeleteRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;
}
