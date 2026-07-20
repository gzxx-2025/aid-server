package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量对口型请求DTO（逐分镜：台词现场 TTS 配音 + 分镜视频提交对口型模型）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardLipSyncBatchRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影传 0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 目标分镜ID列表；为空=该剧集下全部「有台词」的分镜 */
    private List<Long> storyboardIds;

    /** 兜底音色库ID：分镜解析不出「角色→绑定音色」时用此音色 TTS */
    private Long voiceLibraryId;

    /** 兜底配音模型ID（兼容老入参，与 timbreCode 成对使用；voiceLibraryId 存在时忽略） */
    private Long voiceModelId;

    /** 兜底音色编码（兼容老入参，与 voiceModelId 成对使用） */
    private String timbreCode;

    /** 是否重做：默认 false=已有对口型产物的分镜跳过；true=重新对口型（只新增记录，不覆盖） */
    private Boolean overwrite;

    /** 情感（可选，供应商原生编码，仅音色支持情感时生效） */
    private String emotion;

    /** 情感强度（可选，1~5） */
    @Min(value = 1, message = "情感强度无效")
    @Max(value = 5, message = "情感强度无效")
    private Integer emotionScale;

    /** 语速偏移（可选，[-50,100]） */
    @Min(value = -50, message = "语速无效")
    @Max(value = 100, message = "语速无效")
    private Integer speechRate;

    /** 音量偏移（可选，[-50,100]） */
    @Min(value = -50, message = "音量无效")
    @Max(value = 100, message = "音量无效")
    private Integer loudnessRate;

    /** 语调偏移（可选，半音 [-12,12]） */
    @Min(value = -12, message = "音调无效")
    @Max(value = 12, message = "音调无效")
    private Integer pitch;
}
