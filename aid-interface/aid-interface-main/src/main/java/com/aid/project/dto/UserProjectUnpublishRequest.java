package com.aid.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户项目关闭公开请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectUnpublishRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;
}
