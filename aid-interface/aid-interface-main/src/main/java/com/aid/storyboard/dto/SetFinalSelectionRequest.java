package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认最终产物请求DTO
 *
 * @author 视觉AID
 */
@Data
public class SetFinalSelectionRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long storyboardId;

    /** 记录ID(gen_record或audio_task的ID) */
    @NotNull(message = "记录ID不能为空")
    private Long recordId;

    /** 产物类型(image图片, video视频, audio配音) */
    @NotBlank(message = "产物类型不能为空")
    private String recordType;
}
