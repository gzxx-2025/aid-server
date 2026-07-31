package com.aid.publish.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-发布白名单添加请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminWhitelistAddRequest {

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 备注（加入原因等，可空） */
    private String remark;
}
