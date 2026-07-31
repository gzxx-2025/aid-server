package com.aid.publish.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-用户发布权限设置请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminPublishPermissionRequest {

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 发布权限：1允许 0禁止 */
    @NotNull(message = "发布权限不能为空")
    private Integer publishEnabled;
}
