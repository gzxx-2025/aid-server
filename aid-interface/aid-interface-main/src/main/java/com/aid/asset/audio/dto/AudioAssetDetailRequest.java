package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 音频资产详情查询请求（C 端 POST 契约）
 *
 * @author 视觉AID
 */
@Data
public class AudioAssetDetailRequest {

    /** 资产主键（aid_audio_asset.id） */
    @NotNull(message = "主键不能为空")
    private Long id;
}
