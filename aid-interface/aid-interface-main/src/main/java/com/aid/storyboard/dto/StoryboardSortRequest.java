package com.aid.storyboard.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 批量调整分镜排序请求DTO
 * 前端拖拽排序后，将排好序的ID列表传过来
 *
 * @author 视觉AID
 */
@Data
public class StoryboardSortRequest {

    /** 排好序的分镜ID列表(下标即为新序号) */
    @NotEmpty(message = "排序列表不能为空")
    private List<Long> sortedIds;
}
