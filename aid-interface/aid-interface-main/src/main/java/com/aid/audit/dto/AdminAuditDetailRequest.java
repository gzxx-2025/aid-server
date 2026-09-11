package com.aid.audit.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-审核详情查询请求DTO（项目/剧集通用）
 *
 * @author 视觉AID
 */
@Data
public class AdminAuditDetailRequest {

    /** 审核对象ID（项目ID或剧集ID） */
    @NotNull(message = "对象ID不能为空")
    private Long id;
}
