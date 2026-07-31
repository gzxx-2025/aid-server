package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 参考音频重命名请求。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioRenameRequest {

    /** 参考音频主键（aid_reference_audio.id） */
    @NotNull(message = "主键不能空")
    private Long id;

    /** 新音频名称 */
    @NotBlank(message = "名称不能空")
    @Size(max = 128, message = "名称过长")
    private String audioName;
}
