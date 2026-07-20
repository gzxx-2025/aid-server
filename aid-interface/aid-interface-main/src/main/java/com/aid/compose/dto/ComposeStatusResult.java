package com.aid.compose.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 接口1 合成进度查询出参（纯轮询）。
 * 前端拿到 voiceover 返回的 composeBatchId 后，定时轮询本结构直到 {@code status} 进入终态
 * （SUCCEEDED / FAILED）。配音阶段用 audioTotal/audioSucceeded/audioFailed 展示进度，
 * 合成成功后 videoUrl 为成片地址。
 *
 * @author 视觉AID
 */
@Data
public class ComposeStatusResult {

    /** 合成批次号 */
    private String composeBatchId;

    /**
     * 整体状态：
     * VOICING=配音进行中；COMPOSING=配音已齐、合成中；SUCCEEDED=合成成功；FAILED=失败。
     */
    private String status;

    /** 本批配音总数 */
    private Integer audioTotal;

    /** 已完成配音数 */
    private Integer audioSucceeded;

    /** 失败配音数 */
    private Integer audioFailed;

    /** 成片视频地址（仅 status=SUCCEEDED 时非空，出参自动拼 OSS 域名） */
    @MediaUrl
    private String videoUrl;

    /** 成片时长（秒，仅成功时非空） */
    private Long videoDuration;

    /** 失败短文案（仅 status=FAILED 时非空） */
    private String errorMessage;
}
