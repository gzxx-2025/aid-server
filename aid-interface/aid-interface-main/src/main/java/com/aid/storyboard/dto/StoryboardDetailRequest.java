package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询分镜详情请求DTO
 *
 * @author 视觉AID
 */
@Data
public class StoryboardDetailRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long id;
}
