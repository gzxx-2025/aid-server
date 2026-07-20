package com.aid.asset.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户资产删除请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserAssetDeleteRequest {

    /** 资产ID */
    @NotNull(message = "资产ID不能为空")
    private Long id;
}
