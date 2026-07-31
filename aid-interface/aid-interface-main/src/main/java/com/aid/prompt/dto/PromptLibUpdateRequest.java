package com.aid.prompt.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提示词修改请求DTO
 *
 * @author 视觉AID
 */
@Data
public class PromptLibUpdateRequest {

    /** 主键ID */
    @NotNull(message = "提示词ID不能为空")
    private Long id;

    /** 提示词名称 */
    @Size(max = 100, message = "提示词名称不能超过100个字符")
    private String promptName;

    /** 提示词具体内容 */
    private String promptContent;

    /** 效果预览图URL（入参自动剥离域名入库） */
    @MediaUrl
    private String coverUrl;

    /** 备注 */
    private String remark;
}
