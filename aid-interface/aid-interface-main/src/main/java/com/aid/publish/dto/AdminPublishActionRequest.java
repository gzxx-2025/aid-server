package com.aid.publish.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 后台-发布操作请求DTO（上架/下架/回撤共用）
 *
 * @author 视觉AID
 */
@Data
public class AdminPublishActionRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;

    /** 操作原因：下架/回撤必填，上架可选 */
    private String reason;
}
