package com.aid.prompt.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提示词创建请求DTO
 *
 * @author 视觉AID
 */
@Data
public class PromptLibCreateRequest {

    /** 提示词分类: style/camera/subject */
    @NotBlank(message = "提示词分类不能为空")
    private String promptType;

    /** 提示词名称 */
    @NotBlank(message = "提示词名称不能为空")
    @Size(max = 100, message = "提示词名称不能超过100个字符")
    private String promptName;

    /** 补充提示词内容 (可选) */
    private String promptContent;

    /** 效果预览图URL（入参自动剥离域名入库） */
    @NotBlank(message = "参考图不可为空")
    @MediaUrl
    private String coverUrl;

    /** 备注 */
    private String remark;
}
