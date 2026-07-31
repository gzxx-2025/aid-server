package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询生成记录详情请求DTO
 *
 * @author 视觉AID
 */
@Data
public class GenRecordDetailRequest {

    /** 生成记录ID */
    @NotNull(message = "记录ID不能为空")
    private Long id;
}
