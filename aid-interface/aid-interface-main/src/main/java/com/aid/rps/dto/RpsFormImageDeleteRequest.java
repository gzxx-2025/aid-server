package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除形态图片实例请求 DTO（仅软删图片，不动 form）。
 *
 * @author 视觉AID
 */
@Data
public class RpsFormImageDeleteRequest
{
    /** 图片实例ID（aid_role_prop_scene_form_image.id） */
    @NotNull(message = "图片ID不能为空")
    private Long imageId;
}
