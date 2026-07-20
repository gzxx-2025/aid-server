package com.aid.compose.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 接口1 合成进度查询入参（纯轮询）。
 *
 * @author 视觉AID
 */
@Data
public class ComposeStatusRequest {

    /** 合成批次号（voiceover 受理返回的 composeBatchId）。必填 */
    @NotBlank(message = "批次号不能为空")
    private String composeBatchId;
}
