package com.aid.compose.service;

import com.aid.compose.dto.ComposeAcceptResult;
import com.aid.compose.dto.ComposeStatusRequest;
import com.aid.compose.dto.ComposeStatusResult;
import com.aid.compose.dto.EpisodeExportRequest;
import com.aid.compose.dto.EpisodeExportResult;
import com.aid.compose.dto.EpisodeExportStatusRequest;
import com.aid.compose.dto.EpisodeExportStatusResult;
import com.aid.compose.dto.StoryboardComposeRequest;

/**
 * 视频合成业务编排服务（接口1、接口2）。
 * 承接业务语义：取素材、落业务表、串配音→合成事件链、调用核心合成方法 {@code CoreComposeService}。
 *
 * @author 视觉AID
 */
public interface VideoComposeService {

    /**
     * 接口1：分镜一键配音 + 合成。
     * 生成 composeBatchId，逐条对分镜视频异步发起配音（落 aid_audio_record、写 compose_batch_id），
     * 暂存待触发上下文，立即返回受理结果；配音齐全后由事件链自动触发合成。
     *
     * @param request 接口1 入参
     * @return 受理结果（composeBatchId + 配音记录ID + ACCEPTED）
     */
    ComposeAcceptResult composeWithVoiceover(StoryboardComposeRequest request);

    /**
     * 接口2：前端剪辑器直接拼接合成（导出成片）。
     * 定位或自动创建 aid_episode_editor（校验归属，防越权、防重复提交），
     * 置 exportStatus=1、覆盖 timelineJson，调用核心方法提交合成，回填 exportTaskId。
     *
     * @param request 接口2 入参
     * @return 导出受理结果（episodeEditorId + exportTaskId + exportStatus=1）
     */
    EpisodeExportResult exportEpisode(EpisodeExportRequest request);

    /**
     * 接口2 导出进度查询（纯轮询）。
     * 按 episodeEditorId 或 projectId+episodeId 查询本人剪辑记录的导出状态、进度与成片地址。
     *
     * @param request 入参（episodeEditorId 与 projectId+episodeId 二选一）
     * @return 导出进度结果
     */
    EpisodeExportStatusResult queryExportStatus(EpisodeExportStatusRequest request);

    /**
     * 接口1 合成进度查询（纯轮询）。
     * 按 composeBatchId 聚合本批配音进度与合成任务状态：配音未齐返回 VOICING、配音齐但合成未完成返回
     * COMPOSING、合成成功返回 SUCCEEDED（含成片地址）、配音或合成失败返回 FAILED。仅本人可查。
     *
     * @param request 入参（composeBatchId 必填）
     * @return 合成进度结果
     */
    ComposeStatusResult queryComposeStatus(ComposeStatusRequest request);
}
