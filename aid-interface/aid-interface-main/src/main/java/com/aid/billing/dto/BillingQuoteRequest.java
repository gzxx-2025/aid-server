package com.aid.billing.dto;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 权威计费报价请求；payload 直接沿用对应媒体生成 DTO 的 JSON 结构。 */
@Data
public class BillingQuoteRequest
{
    @NotBlank(message = "报价类型不能为空")
    @Pattern(regexp = "MEDIA_(IMAGE|IMAGE_PRICING|VIDEO|TEXT|AUDIO)|ASSET_EXTRACT|FORM_(GENERATE|IMAGE|CARD_IMAGE|IMAGE_UPSCALE|EDIT_CHAT_IMAGE|MULTI_VIEW_IMAGE)|STORYBOARD_(SCRIPT|IMAGE|IMAGE_PROMPT|IMAGE_WITH_PROMPT|EDIT_IMAGE|IMAGE_UPSCALE|MULTI_VIEW_IMAGE|MULTI_GRID_IMAGE|VIDEO|VIDEO_IMAGE|VIDEO_GRID|VIDEO_EDGE|VIDEO_PROMPT|VIDEO_PROMPT_IMAGE|VIDEO_PROMPT_GRID|VIDEO_WITH_PROMPT|AUDIO|AUDIO_BATCH|LIP_SYNC|LIP_SYNC_BATCH)|TASK_RESUME",
            message = "报价类型不支持")
    private String quoteType;

    @NotNull(message = "报价参数不能为空")
    private JsonNode payload;

    /** 同一 payload 重复调用次数；payload 内输出张数仍是每次调用输出量，不得传批量 items。 */
    @Min(value = 1, message = "报价数量无效")
    @Max(value = 100, message = "报价数量过大")
    private Integer quantity = 1;
}
