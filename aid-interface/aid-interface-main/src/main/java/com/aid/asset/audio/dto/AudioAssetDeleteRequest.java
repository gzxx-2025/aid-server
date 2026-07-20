package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 音频资产删除请求
 *
 * @author 视觉AID
 */
@Data
public class AudioAssetDeleteRequest {

    /** 资产主键（aid_audio_asset.id） */
    @NotNull(message = "主键不能为空")
    private Long id;
}
