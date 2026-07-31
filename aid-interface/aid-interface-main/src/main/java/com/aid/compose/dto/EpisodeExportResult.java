package com.aid.compose.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 接口2 出参：导出受理结果。
 * 素材未变命中复用时直接返回成片（exportStatus=2 + finalVideoUrl，无需轮询）；
 * 否则为异步受理（exportStatus=1），前端轮询导出进度查询接口获取终态。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeExportResult {

    /**
     * 剧集剪辑记录ID（aid_episode_editor.id）。
     * 入参未传时为后端本次自动创建的记录ID，前端必须保存，用于轮询进度与下次导出。
     */
    private Long episodeEditorId;

    /** 导出任务标识（后端合成任务 aid_media_task.id 的字符串形式）；同步字幕受理阶段可能为空 */
    private String exportTaskId;

    /** 导出状态：1=合成中（轮询进度接口）；2=成功（命中复用直接返回，取 finalVideoUrl，无需轮询） */
    private Integer exportStatus;

    /** 是否复用已有成片：true=直接返回既有成片；false=异步任务路径（新受理或幂等返回运行中任务） */
    private Boolean reused;

    /** 成片视频地址（出参自动拼域名）；仅 reused=true（exportStatus=2）时非空，可直接播放/下载 */
    @MediaUrl
    private String finalVideoUrl;
}
