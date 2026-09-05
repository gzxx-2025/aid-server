package com.aid.compose.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 导出进度查询出参（纯轮询）。
 * 轮询节奏由前端按页面状态自行控制：exportStatus=1 继续轮询；=2 取 finalVideoUrl 播放/下载；
 * =3 取 errorMsg 提示后停止。
 *
 * @author 视觉AID
 */
@Data
public class EpisodeExportStatusResult {

    /** 剧集剪辑记录ID（aid_episode_editor.id） */
    private Long episodeEditorId;

    /** 所属项目ID（aid_comic_project.id） */
    private Long projectId;

    /** 所属剧集ID（aid_comic_episode.id，电影为 0） */
    private Long episodeId;

    /** 导出状态：0=未导出/待重新导出，1=合成中，2=导出成功，3=导出失败 */
    private Integer exportStatus;

    /** 导出进度百分比（0-100），成功后为 100 */
    private Integer exportProgress;

    /** 最新成片视频地址（仅 exportStatus=2 时非空，出参自动拼 OSS 访问域名） */
    @MediaUrl
    private String finalVideoUrl;

    /** 成片预览封面图地址（可空，出参自动拼 OSS 访问域名） */
    @MediaUrl
    private String coverUrl;

    /** 导出失败原因（仅 exportStatus=3 时非空） */
    private String errorMsg;

    /** 导出任务标识（合成任务ID字符串），用于排查问题 */
    private String exportTaskId;

}
