package com.aid.storyboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 分镜机位生图响应 VO（{@code POST /api/user/storyboard/generate/multi-view-grid-image} 出参）。
 * 提交后返回 {@code taskId + PENDING}，前端经 {@code aid_extract_task} 任务列表 / SSE 获取结果；
 * 终态结果落 {@code aid_gen_record}（{@code gen_type=image} 或 {@code grid}）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class StoryboardMultiViewGridImageGenerateVO
{
    /** 任务 ID（{@code aid_extract_task.id}），前端轮询 / SSE 主键 */
    private Long taskId;

    /** 任务状态（提交后必为 {@code PENDING}） */
    private String status;
}
