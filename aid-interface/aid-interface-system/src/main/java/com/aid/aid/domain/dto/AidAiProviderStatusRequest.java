package com.aid.aid.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 模型服务商启用 / 停用请求。
 *
 * @author 视觉AID
 */
@Data
public class AidAiProviderStatusRequest
{
    /** 服务商主键 */
    @NotNull(message = "主键不能为空")
    private Long id;

    /** 目标状态：0启用，1停用 */
    @NotBlank(message = "状态不能为空")
    private String status;
}
