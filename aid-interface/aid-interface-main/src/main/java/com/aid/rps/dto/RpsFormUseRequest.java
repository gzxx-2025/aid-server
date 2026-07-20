package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 从表形态使用状态请求DTO
 *
 * @author 视觉AID
 */
@Data
public class RpsFormUseRequest {

    /** 从表形态ID */
    @NotNull(message = "形态ID不能为空")
    private Long id;
}
