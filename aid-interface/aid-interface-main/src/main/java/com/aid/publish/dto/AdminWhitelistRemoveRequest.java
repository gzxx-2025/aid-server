package com.aid.publish.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-发布白名单移除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class AdminWhitelistRemoveRequest {

    /** 白名单记录ID */
    @NotNull(message = "记录ID不能为空")
    private Long id;
}
