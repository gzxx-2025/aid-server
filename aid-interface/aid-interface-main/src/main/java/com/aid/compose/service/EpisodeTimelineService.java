package com.aid.compose.service;

import com.aid.compose.dto.timeline.EpisodeTimelineGetRequest;
import com.aid.compose.dto.timeline.EpisodeTimelineResult;
import com.aid.compose.dto.timeline.EpisodeTimelineSaveRequest;

/**
 * 剧集剪辑时间轴服务：aid_episode_editor.timeline_json 的读取（带自动初始化）与保存。
 *
 * @author 视觉AID
 */
public interface EpisodeTimelineService {

    /**
     * 读取时间轴工程。
     * 剪辑记录不存在时自动建档；timeline_json 为空（或 rebuild=true）时按该剧集分镜数据
     * 自动初始化：分镜视频（最终选中，无则最新成功）、配音（含音色参数快照）、
     * 字幕（台词格式化为「人物：说的话」）、默认音量/字号，初始化结果落库后返回。
     *
     * @param request 入参（episodeEditorId 与 projectId+episodeId 二选一）
     * @return 剪辑记录元信息 + 时间轴工程（URL 已拼完整域名）
     */
    EpisodeTimelineResult getTimeline(EpisodeTimelineGetRequest request);

    /**
     * 保存时间轴工程（整份覆盖）。
     * 校验资源 URL 必须为链接（拒绝 Base64/data URI）、音量/字号越界收敛到合法范围、
     * 补默认值并重算总时长后存入 timeline_json；若当前已是导出终态（成功/失败），
     * 保存后导出状态回置 0（工程已修改待重新导出）。
     *
     * @param request 入参（定位字段 + 完整 timeline）
     * @return 保存后的剪辑记录元信息 + 时间轴工程（URL 已拼完整域名）
     */
    EpisodeTimelineResult saveTimeline(EpisodeTimelineSaveRequest request);
}
