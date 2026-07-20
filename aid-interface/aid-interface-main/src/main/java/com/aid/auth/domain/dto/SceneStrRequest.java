package com.aid.auth.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信扫码场景值请求DTO
 *
 * @author 视觉AID
 */
@Data
public class SceneStrRequest {

    /** 场景值 */
    @NotBlank(message = "场景值不能为空")
    private String sceneStr;
}
