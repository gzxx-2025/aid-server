package com.aid.faq.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 常见问题详情查询请求（C 端）
 *
 * @author 视觉AID
 */
@Data
public class FaqDetailRequest
{
    /** 常见问题ID（必填） */
    @NotNull(message = "ID不能为空")
    private Long id;
}
