package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除资产请求DTO
 *
 * @author 视觉AID
 */
@Data
public class RpsDeleteRequest {

    /** 主表资产ID */
    @NotNull(message = "资产ID不能为空")
    private Long id;

    /** 从表形态ID（不传则删除主表及所有关联从表） */
    private Long formId;
}
