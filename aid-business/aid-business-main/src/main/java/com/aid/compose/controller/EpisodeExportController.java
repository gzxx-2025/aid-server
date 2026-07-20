package com.aid.compose.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.compose.dto.EpisodeExportRequest;
import com.aid.compose.dto.EpisodeExportResult;
import com.aid.compose.dto.EpisodeExportStatusRequest;
import com.aid.compose.dto.EpisodeExportStatusResult;
import com.aid.compose.dto.EpisodeFinalVideoDownloadRequest;
import com.aid.compose.dto.EpisodeSegmentVideosRequest;
import com.aid.compose.dto.EpisodeSegmentVideosResult;
import com.aid.compose.dto.EpisodeSegmentZipDownloadRequest;
import com.aid.compose.service.EpisodeFinalVideoDownloadService;
import com.aid.compose.service.EpisodeSegmentExportService;
import com.aid.compose.service.EpisodeSegmentZipService;
import com.aid.compose.service.VideoComposeService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 接口2：前端剪辑器直接拼接合成（C 端）。
 * 接收前端剪辑器的分组 URL 数据（视频/配音/字幕/背景音）与工程报文，直接调用核心合成方法提交 MPS 合成任务。
 * 本接口为受理型异步接口：先将剧集剪辑置「合成中」并覆盖 timelineJson，同步返回 episodeEditorId + exportTaskId，
 * 前端随后轮询 /status 获取导出终态（成片地址 / 失败原因）。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/episode/export")
public class EpisodeExportController extends BaseController {

    /** 视频合成业务编排服务 */
    @Resource
    private VideoComposeService videoComposeService;

    /** 分段素材批量导出服务 */
    @Resource
    private EpisodeSegmentExportService episodeSegmentExportService;

    /** 分段素材流式打包下载服务 */
    @Resource
    private EpisodeSegmentZipService episodeSegmentZipService;

    /** 成片流式下载服务 */
    @Resource
    private EpisodeFinalVideoDownloadService episodeFinalVideoDownloadService;

    /**
     * 前端剪辑器直接拼接合成（导出成片）。
     * URL：POST /api/user/episode/export
     * 用户身份由登录态解析（Service 内取 SecurityUtils），请求体不携带 userId。
     * episodeEditorId 与 projectId+episodeId 二选一：首次导出只传 projectId+episodeId，
     * 后端自动创建剪辑记录并回传 episodeEditorId；再次导出可直接传 episodeEditorId。
     * 每组 videoDurations / audioDurations 必须与对应 URL 列表等长且每项大于 0（决定对齐与扣费）。
     * 上一次导出仍在合成中时重复提交会被拒绝（文案「合成中请稍候」）。
     *
     * @param request 导出入参（episodeEditorId 或 projectId+episodeId + 分组URL数据 + 可选整片BGM/分辨率/timelineJson）
     * @return 导出受理结果（episodeEditorId + exportTaskId + exportStatus=1 合成中）
     */
    @PostMapping
    public AjaxResult export(@RequestBody EpisodeExportRequest request) {
        EpisodeExportResult result = videoComposeService.exportEpisode(request);
        return success(result);
    }

    /**
     * 导出进度查询（纯轮询）。
     * URL：POST /api/user/episode/export/status
     * 按 episodeEditorId（优先）或 projectId+episodeId 查询本人剪辑记录的导出状态；
     * 建议每 2~3 秒轮询一次：exportStatus=1 继续轮询，=2 取 finalVideoUrl，=3 取 errorMsg 后停止。
     *
     * @param request 进度查询入参（episodeEditorId 与 projectId+episodeId 二选一）
     * @return 导出进度（exportStatus/exportProgress/finalVideoUrl/coverUrl/errorMsg）
     */
    @PostMapping("/status")
    public AjaxResult status(@RequestBody EpisodeExportStatusRequest request) {
        EpisodeExportStatusResult result = videoComposeService.queryExportStatus(request);
        return success(result);
    }

    /**
     * 分段素材批量导出清单（只读查询，不产生任务与扣费）。
     * URL：POST /api/user/episode/export/segments
     * 按分镜顺序返回每一段的：最终视频（被设为主视频的抽卡记录）、配音音频（优先最终选中，
     * 其次最新成功）、对口型合成视频（画面+配音合一，有则前端优先下载）、格式化字幕文本。
     * 用于「批量导出分段视频」：前端拿清单后逐个下载文件（与导出成片接口互不影响）。
     *
     * @param request 入参（projectId + episodeId 必填，电影传 episodeId=0）
     * @return 分段清单（URL 已拼域名可直接下载）+ 就绪统计
     */
    @PostMapping("/segments")
    public AjaxResult segments(@RequestBody EpisodeSegmentVideosRequest request) {
        EpisodeSegmentVideosResult result = episodeSegmentExportService.listSegmentVideos(request);
        return success(result);
    }

    /**
     * 分段素材打包下载（流式 zip，只读，不产生任务与扣费）。
     * URL：POST /api/user/episode/export/segments/zip
     * 把本项目/剧集下全部分镜的素材（分镜图/分镜视频/配音/字幕 txt）按「分镜01_标题/」目录结构
     * 打成一个 zip 并流式下发：后端边从对象存储拉流边写响应，不落磁盘不整包驻留内存
     * （内存占用恒定几 MB，与素材总体积无关），前端以文件流接收保存。
     * 单个素材源拉取失败会跳过并记入包内「导出说明.txt」，不中断整包。
     * 标注 @CryptoIgnore：响应为二进制 zip 流，不参与本平台 API 加解密（请求体明文 JSON）。
     *
     * @param request  入参（projectId + episodeId 必填；includeImages/Videos/Audios/Subtitles 可选，缺省全含）
     * @param response HTTP 响应（application/zip 附件流，文件名「项目名_剧集标识_时间标识.zip」，电影的剧集标识为「完结」）
     */
    @CryptoIgnore
    @PostMapping("/segments/zip")
    public void segmentsZip(@RequestBody EpisodeSegmentZipDownloadRequest request, HttpServletResponse response) {
        episodeSegmentZipService.streamSegmentsZip(request, response);
    }

    /**
     * 成片流式下载（附件流，只读，不产生任务与扣费）。
     * URL：POST /api/user/episode/export/download
     * 把最新导出成片以 video/mp4 附件流直发（后端边从对象存储拉流边写响应，
     * 不落磁盘、不整片驻留内存），前端以文件流接收保存；待审新片优先于公开成片。
     * 无成片（从未导出成功）返回「暂无成片」；合成中仍可下载上一次成片（如有）。
     * 标注 @CryptoIgnore：响应为二进制视频流，不参与本平台 API 加解密（请求体明文 JSON）。
     *
     * @param request  入参（episodeEditorId 与 projectId+episodeId 二选一）
     * @param response HTTP 响应（video/mp4 附件流，文件名「项目名_剧集标识_成片_时间.mp4」）
     */
    @CryptoIgnore
    @PostMapping("/download")
    public void download(@RequestBody EpisodeFinalVideoDownloadRequest request, HttpServletResponse response) {
        episodeFinalVideoDownloadService.streamFinalVideo(request, response);
    }
}
