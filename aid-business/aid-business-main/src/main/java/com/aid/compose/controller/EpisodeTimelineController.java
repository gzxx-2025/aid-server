package com.aid.compose.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.compose.dto.timeline.EpisodeTimelineGetRequest;
import com.aid.compose.dto.timeline.EpisodeTimelineResult;
import com.aid.compose.dto.timeline.EpisodeTimelineSaveRequest;
import com.aid.compose.service.EpisodeTimelineService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 剧集剪辑时间轴接口（C 端，成品预览/剪辑器页）。
 * timeline_json 采用「段 × 轨」结构：segments 按分镜顺序排列，段内挂视频/配音/字幕三轨元素，
 * 背景音乐为全片级单独一轨；所有资源以 URL 链接存储（库内相对路径，出参拼完整域名）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/episode/timeline")
public class EpisodeTimelineController extends BaseController {

    /** 剧集剪辑时间轴服务 */
    @Resource
    private EpisodeTimelineService episodeTimelineService;

    /**
     * 读取时间轴工程（带自动初始化）。
     * URL：POST /api/user/episode/timeline/get
     * 用户身份由登录态解析，请求体不携带 userId。
     * 剪辑记录不存在自动建档；timeline_json 为空（或 rebuild=true）时按分镜数据自动初始化：
     * 视频=最终选中的分镜视频（无则最新成功，仍无则该段留空）、配音=最终选中配音（含音色名称/
     * 语速/音调等参数快照）、字幕=台词格式化「人物：说的话」、音量/字号等给默认值，结果落库后返回。
     *
     * @param request 入参（episodeEditorId 与 projectId+episodeId 二选一 + 可选 rebuild）
     * @return 剪辑记录元信息 + 完整时间轴（资源 URL 已拼域名，可直接播放）
     */
    @PostMapping("/get")
    public AjaxResult get(@RequestBody EpisodeTimelineGetRequest request) {
        EpisodeTimelineResult result = episodeTimelineService.getTimeline(request);
        return success(result);
    }

    /**
     * 保存时间轴工程（整份覆盖）。
     * URL：POST /api/user/episode/timeline/save
     * 前端修改任意轨道（换视频/改字幕文本与字号/调音量/选背景音乐等）后传完整 timeline 覆盖保存；
     * 资源 URL 必须为链接（拒绝 Base64），音量 0-100、字号 12-120 越界自动收敛，总时长后端重算；
     * 已导出成功/失败的记录保存后导出状态回置 0（工程已修改待重新导出）。
     *
     * @param request 入参（定位字段 + 完整 timeline）
     * @return 保存后的剪辑记录元信息 + 时间轴（资源 URL 已拼域名）
     */
    @PostMapping("/save")
    public AjaxResult save(@RequestBody EpisodeTimelineSaveRequest request) {
        EpisodeTimelineResult result = episodeTimelineService.saveTimeline(request);
        return success(result);
    }
}
