package com.aid.aid.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 系统提示词修改请求DTO
 * 仅允许修改 main_business_prompt / main_teacher_prompt 类型的提示词及版本号
 *
 * @author 视觉AID
 */
@Data
public class SystemPromptUpdateRequest {

    /** 主键ID */
    @NotNull(message = "ID不能为空")
    private Long id;

    /** 提示词名称 */
    private String promptName;

    /** 中文提示词内容 */
    private String promptContent;

    /** 英文提示词内容 */
    private String promptContentEn;

    /** 版本号 */
    private Integer version;

    /** 排序 */
    private Long sortOrder;

    /** 状态 (0正常 1停用) */
    private String status;
}
