package com.aid.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提示词文件查询请求DTO
 *
 * @author 视觉AID
 */
@Data
public class PromptFileQueryRequest {

    /** 提示词分类（仅允许: main_business_prompt, main_teacher_prompt） */
    @NotBlank(message = "提示词分类不能为空")
    private String promptType;

    /** 文件名（对应aid_prompt_lib.remark字段） */
    @NotBlank(message = "备注文件名不能为空")
    private String remark;

    /** 语言（zh=中文, en=英文，不传则返回中英文） */
    private String lang;
}
