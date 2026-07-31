package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 删除分镜请求DTO（单删 / 批删统一入口）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardDeleteRequest {

    /** 分镜ID列表（单删传 1 个，批删传多个，单次最多 200 个，由 Service 校验） */
    @NotEmpty(message = "分镜ID不能为空")
    private List<Long> ids;
}
