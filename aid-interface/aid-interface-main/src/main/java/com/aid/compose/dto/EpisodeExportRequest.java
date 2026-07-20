package com.aid.compose.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口2 入参：前端剪辑器直接拼接合成（导出成片）。
 * episodeEditorId 与 projectId+episodeId 二选一：首次导出（还没有剪辑记录）只传
 * projectId+episodeId，后端自动查找或创建 aid_episode_editor 记录并在出参回传
 * episodeEditorId，前端保存后续复用；再次导出可直接传 episodeEditorId。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeExportRequest {

    /**
     * 剧集剪辑记录ID（aid_episode_editor.id）。
     * 来源：本接口首次导出时出参回传的 episodeEditorId，或导出进度查询接口的出参。
     * 可空：为空时必须传 projectId + episodeId，后端按「当前用户 + 项目 + 剧集」查找或自动创建。
     */
    private Long episodeEditorId;

    /**
     * 项目ID（aid_comic_project.id）。
     * 来源：项目列表/详情接口。episodeEditorId 为空时必填；不为空时可省略（以剪辑记录为准）。
     */
    private Long projectId;

    /**
     * 剧集ID（aid_comic_episode.id）。
     * 来源：剧集列表/详情接口。电影（项目级成片）固定传 0。
     * episodeEditorId 为空时必填；不为空时可省略（以剪辑记录为准）。
     */
    private Long episodeId;

    /**
     * 整片背景音乐 URL，可空。来源：素材库/资产中心返回的音频地址（相对路径或完整 URL 均可）。
     * 非空时铺满整片并压低音量，各分组 bgmUrl 失效（互斥，整片优先）。
     */
    private String globalBgmUrl;

    /** 合成分组列表（时间轴上的一「段」），必填且不为空，按数组顺序首尾相接拼成成片 */
    private List<ComposeGroupDto> groups;

    /**
     * 输出分辨率档，可空，默认 FHD。
     * 可选值：SD / HD / FHD / 2K / 4K（大小写不敏感）。档位越高扣费越高。
     */
    private String resolution;

    /**
     * 前端剪辑器工程报文（轨道、素材排版等配置 JSON 字符串），可空。
     * 非空时覆盖存入 aid_episode_editor.timeline_json，供剪辑器下次打开还原时间轴。
     * 后端不解析其内容，仅原样存取。
     */
    private String timelineJson;

    /**
     * 是否强制重新合成，可空默认 false。
     * false：素材与上次成功导出完全一致（视频/配音/字幕/BGM/分辨率均未变）时直接复用已有成片，
     * 不再发起合成、不再扣费；true：忽略复用缓存，强制重新合成一次（重新扣费）。
     */
    private Boolean forceRecompose;
}
