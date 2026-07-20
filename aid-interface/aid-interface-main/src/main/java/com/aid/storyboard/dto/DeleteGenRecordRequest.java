package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除分镜生成记录请求 DTO。
 *
 * @author 视觉AID
 */
@Data
public class DeleteGenRecordRequest {

    /** 分镜 ID（必填，必须存在、未删除、归属当前用户，用于越权校验） */
    @NotNull(message = "分镜不存在")
    private Long storyboardId;

    /** 生成记录 ID（必填，必须属于该分镜且归属当前用户） */
    @NotNull(message = "记录不存在")
    private Long recordId;
}
