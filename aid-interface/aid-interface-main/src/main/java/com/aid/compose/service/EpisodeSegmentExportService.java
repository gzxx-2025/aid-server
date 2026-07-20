package com.aid.compose.service;

import com.aid.compose.dto.EpisodeSegmentVideosRequest;
import com.aid.compose.dto.EpisodeSegmentVideosResult;

/**
 * 分段素材批量导出服务：按项目+剧集列出每个分镜的最终视频（主视频）与配音，
 * 供前端批量下载分段素材（区别于导出接口产出的整片成片）。
 *
 * @author 视觉AID
 */
public interface EpisodeSegmentExportService {

    /**
     * 查询分段素材导出清单。
     * 按分镜 sort_order 升序返回每段：最终视频（final_video_id 指向的抽卡记录）、
     * 配音（优先 final_audio_id，其次最新成功配音）、对口型合成视频、格式化字幕。
     * 仅可查本人项目（防越权）。
     *
     * @param request 入参（projectId + episodeId 必填）
     * @return 分段清单（URL 已拼完整域名）+ 就绪统计
     */
    EpisodeSegmentVideosResult listSegmentVideos(EpisodeSegmentVideosRequest request);
}
