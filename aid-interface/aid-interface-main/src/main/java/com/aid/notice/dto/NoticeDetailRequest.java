package com.aid.notice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 公告详情查询请求（C 端）
 *
 * @author 视觉AID
 */
@Data
public class NoticeDetailRequest
{
    /** 公告ID（必填） */
    @NotNull(message = "ID不能为空")
    private Long id;
}
