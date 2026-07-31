package com.aid.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 音色库启用 / 停用请求 DTO
 *
 * @author 视觉AID
 */
@Data
public class VoiceLibraryStatusRequest
{
    /** 主键 */
    @NotNull(message = "主键不能为空")
    private Long id;

    /** 目标状态：0启用 1停用 */
    @NotBlank(message = "状态不能为空")
    private String status;
}
