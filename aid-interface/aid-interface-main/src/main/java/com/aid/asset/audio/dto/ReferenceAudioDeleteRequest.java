package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 参考音频删除请求。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioDeleteRequest {

    /** 参考音频主键（aid_reference_audio.id） */
    @NotNull(message = "主键不能空")
    private Long id;
}
