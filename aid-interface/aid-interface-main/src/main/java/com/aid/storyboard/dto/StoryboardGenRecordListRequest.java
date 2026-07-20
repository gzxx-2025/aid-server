package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 项目维度"已生成内容"列表查询请求 DTO（{@code POST /api/user/storyboard/record/list-by-storyboard}）。
 * 按项目 + 剧集 + 类型（图片 / 分镜视频 / 配音视频）过滤当前用户的生成记录。{@code episodeId} 取值：电影项目传 {@code 0}，
 * 剧集项目传 &gt; 0 的实际剧集 ID。是否被选为最终分镜由返回 VO 的 {@code isSelected} 字段表示。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardGenRecordListRequest
{
    /** 项目 ID（必填，须存在、未删除、归属当前用户）。 */
    @NotNull(message = "项目不存在")
    private Long projectId;

    /** 剧集 ID（必填）：电影项目传 {@code 0}，剧集项目传实际剧集 ID；配对校验由 Service 层完成。 */
    @NotNull(message = "剧集不能空")
    private Long episodeId;

    /**
     * 类型：{@code image}（图片，含 image / grid）、{@code video}（分镜视频＝原视频轨，含
     * i2v / multi / edge / upload_video，不含配音视频）、{@code compose}（配音视频，含一键配音 /
     * 批量配音合成视频与对口型视频）。
     */
    @NotBlank(message = "类型不能空")
    private String type;
}
