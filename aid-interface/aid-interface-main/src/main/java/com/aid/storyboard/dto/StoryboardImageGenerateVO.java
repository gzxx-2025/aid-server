package com.aid.storyboard.dto;

import java.util.List;

import lombok.Data;

/**
 * 分镜图生成响应 VO（支持单/多镜头批量）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardImageGenerateVO
{
    /** 父任务 ID（aid_extract_task.id），前端 SSE 订阅 / 取消 / 轮询主键 */
    private Long taskId;

    /** 任务状态（PENDING / PROCESSING / SUCCEEDED / PARTIAL_FAILED / FAILED / CANCELLED） */
    private String status;

    /** 实际命中的模型 modelCode（整批共用一个模型） */
    private String modelName;

    /** 本次实际进入父任务的镜头数（不含被防重锁占用 / 解析失败被跳过的镜头） */
    private Integer totalShots;

    /** 每个镜头的出图张数（多镜头恒为 1；单镜头为 count，1~8） */
    private Integer countPerShot;

    /** 子任务总数 = totalShots × countPerShot（= 本次提交的图片总张数） */
    private Integer totalSubtasks;

    /** 每个镜头的受理结果明细（含被跳过的镜头及原因，便于前端提示） */
    private List<ShotResult> items;

    /**
     * 单镜头受理结果。
     */
    @Data
    public static class ShotResult
    {
        /** 分镜 ID（aid_storyboard.id） */
        private Long storyboardId;

        /** 是否已受理进入父任务（true=已纳入出图；false=被跳过，见 reason） */
        private Boolean accepted;

        /** 未受理原因（accepted=false 时有值，如「任务处理中」「提示词为空」「参考图缺失」等） */
        private String reason;

        public ShotResult() {}

        public ShotResult(Long storyboardId, Boolean accepted, String reason)
        {
            this.storyboardId = storyboardId;
            this.accepted = accepted;
            this.reason = reason;
        }
    }
}
