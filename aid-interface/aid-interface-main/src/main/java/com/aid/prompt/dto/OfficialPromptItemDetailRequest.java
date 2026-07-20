package com.aid.prompt.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 官方只读参数词库 - 词条详情查询入参
 *
 * @author 视觉AID
 */
@Data
public class OfficialPromptItemDetailRequest {

    /** 词条主键ID */
    @NotNull(message = "ID不能为空")
    private Long id;
}
