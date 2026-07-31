package com.aid.compose.service;

import com.aid.compose.dto.EpisodeSegmentZipDownloadRequest;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 分段素材打包下载服务：把项目/剧集下全部分镜素材（分镜图/视频/配音/字幕）
 * 以「边下边打包边下发」的流式方式写出 zip，服务端内存占用恒定为拷贝缓冲区大小，
 * 与素材总体积无关（1GB 素材也只占用几 MB 内存）。
 *
 * @author 视觉AID
 */
public interface EpisodeSegmentZipService {

    /**
     * 流式打包下载分段素材 zip。
     * 素材逐个从对象存储拉流写入 zip 输出流（不落磁盘、不整包驻留内存），
     * 媒体文件本身已压缩，zip 采用不压缩档位直通拷贝，CPU 开销可忽略。
     * 仅可下载本人项目（防越权）。
     *
     * @param request  入参（projectId + episodeId 必填，includeXxx 可选默认全含）
     * @param response HTTP 响应（本方法直接写出 application/zip 流）
     */
    void streamSegmentsZip(EpisodeSegmentZipDownloadRequest request, HttpServletResponse response);
}
