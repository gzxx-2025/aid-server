package com.aid.compose.domain;

import lombok.Data;

import java.util.List;

/**
 * 核心合成方法入参（URL 驱动、无状态的分组素材数据 + 业务回填目标）。
 * 接口1与接口2共用：接口1由配音就绪事件链装配后传入，接口2由前端分组数据装配后传入。
 * 不依赖任何业务表，纯输入输出。
 *
 * @author 视觉AID
 */
@Data
public class ComposeCommand {

    /** 整片铺满 BGM 的 URL，可空。空则按各组 bgmUrl 处理 */
    private String globalBgmUrl;

    /** 分组列表，必填且不为空 */
    private List<ComposeGroup> groups;

    /** 段对齐策略：VIDEO(默认,以视频为准)/AUDIO */
    private String alignStrategy;

    /** 输出分辨率档（SD/HD/FHD/2K/4K），默认 FHD(1080P) */
    private String resolution;

    /** 计费用户ID */
    private Long userId;

    /** 业务回填目标：gen_record(接口1) / episode_editor(接口2) */
    private String callbackCategory;

    /** 业务回填主键（gen_record.id 或 episode_editor.id） */
    private Long callbackRecordId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 接口1配音→合成事件链批次号（落 aid_media_task.compose_batch_id） */
    private String composeBatchId;
}
