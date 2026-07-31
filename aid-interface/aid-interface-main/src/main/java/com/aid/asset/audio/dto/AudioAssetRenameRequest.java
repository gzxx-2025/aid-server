package com.aid.asset.audio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 音频资产重命名请求
 *
 * @author 视觉AID
 */
@Data
public class AudioAssetRenameRequest {

    /** 资产主键（aid_audio_asset.id） */
    @NotNull(message = "主键不能为空")
    private Long id;

    /** 新资产标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题过长")
    private String assetTitle;
}
