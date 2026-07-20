package com.aid.audit.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-审核操作请求DTO（项目/剧集通用）
 *
 * @author 视觉AID
 */
@Data
public class AdminAuditActionRequest {

    /** 审核对象ID（项目ID或剧集ID） */
    @NotNull(message = "对象ID不能为空")
    private Long id;

    /** 是否通过：true=审核通过，false=审核驳回 */
    @NotNull(message = "审核结果不能为空")
    private Boolean pass;

    /** 审核意见/驳回原因：驳回时必填，通过时可选 */
    private String reason;
}
