package com.aid.compose.dto.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 时间轴读取/保存出参：剪辑记录元信息 + 完整时间轴工程（资源 URL 已拼完整域名）。
 * 出参恒定：所有字段在任何场景下都会返回（无数据的字段为 null / 默认值，绝不缺字段）。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class EpisodeTimelineResult {

    /** 剧集剪辑记录ID（aid_episode_editor.id），首次读取为后端自动创建，前端保存复用 */
    private Long episodeEditorId;

    /** 所属项目ID */
    private Long projectId;

    /** 所属剧集ID（电影为 0） */
    private Long episodeId;

    /** 导出状态：0=未导出/工程已修改待重新导出，1=合成中，2=导出成功，3=导出失败 */
    private Integer exportStatus;

    /** 导出进度百分比（0-100） */
    private Integer exportProgress;

    /** 最新成片视频地址（出参自动拼域名），未导出成功为 null */
    @MediaUrl
    private String finalVideoUrl;

    /** 导出失败原因（仅 exportStatus=3 时非空） */
    private String errorMsg;

    /** 时间轴工程数据（video/voice/bgm 的 url 已转为完整可播放地址） */
    private TimelineData timeline;
}
