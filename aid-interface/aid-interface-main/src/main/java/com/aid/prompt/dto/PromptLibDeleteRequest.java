package com.aid.prompt.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提示词删除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class PromptLibDeleteRequest {

    /** 主键ID */
    @NotNull(message = "提示词ID不能为空")
    private Long id;
}
