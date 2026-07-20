package com.aid.rps.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建形态图片实例请求 DTO（仅 upload / official 两种来源）。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageCreateRequest
{
    /** 关联形态ID */
    @NotNull(message = "形态ID不能为空")
    private Long formId;

    /** 图片URL（入参剥离域名入库） */
    @NotBlank(message = "图片地址不能为空")
    @MediaUrl
    private String imageUrl;

    /** 图片名称（可选，缺省由后端自动生成） */
    @Size(max = 120, message = "图片名称不能超过120个字符")
    private String name;

    /** 来源类型：upload / official */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;
}
