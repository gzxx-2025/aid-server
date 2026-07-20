package com.aid.compose.domain;

import lombok.Data;

import java.util.List;

/**
 * 接口1 合成待触发上下文（按 composeBatchId 暂存于 Redis）。
 * 接口1 受理时只发起异步配音并暂存本上下文；配音就绪事件链判齐后，监听器据此（叠加
 * aid_audio_record 的 audioUrl/durationMs）装配 {@code ComposeCommand} 触发核心合成。
 * 纯配音合成：不含字幕与背景音乐（由成片合成导出阶段处理）。
 *
 * @author 视觉AID
 */
@Data
public class ComposePendingContext {

    /** 合成批次号 */
    private String composeBatchId;

    /** 计费用户ID */
    private Long userId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 输出分辨率档 */
    private String resolution;

    /** 段对齐策略 */
    private String alignStrategy;

    /** 分组上下文（顺序即合成顺序），与 aid_audio_record 通过 audioRecordId 关联 */
    private List<Item> items;

    /**
     * 单组上下文：一条分镜视频 + 其配音记录。
     */
    @Data
    public static class Item {

        /** 配音记录ID（aid_audio_record.id）：判齐与回取 audioUrl/durationMs 的关联键 */
        private Long audioRecordId;

        /** 该组视频 URL（相对路径） */
        private String videoUrl;

        /** 该组视频时长（秒） */
        private Double videoDuration;
    }
}
