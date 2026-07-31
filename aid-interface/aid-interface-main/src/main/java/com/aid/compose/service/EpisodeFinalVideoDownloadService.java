package com.aid.compose.service;

import com.aid.compose.dto.EpisodeFinalVideoDownloadRequest;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 成片流式下载服务：把最新导出成片以附件流直发响应（不落磁盘、不整片驻留内存）。
 *
 * @author 视觉AID
 */
public interface EpisodeFinalVideoDownloadService {

    /**
     * 流式下载最新成片（待审新片优先，其次公开成片）。
     *
     * @param request  下载入参（episodeEditorId 与 projectId+episodeId 二选一）
     * @param response HTTP 响应（video/mp4 附件流）
     */
    void streamFinalVideo(EpisodeFinalVideoDownloadRequest request, HttpServletResponse response);
}
