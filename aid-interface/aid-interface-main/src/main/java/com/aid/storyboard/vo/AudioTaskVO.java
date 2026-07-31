package com.aid.storyboard.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 配音任务VO(返回给前端)
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AudioTaskVO {

    /** 任务ID */
    private Long id;

    /** 分镜ID */
    private Long storyboardId;

    /** 来源(1:AI配音, 2:用户上传) */
    private Integer audioSource;

    /** 音频文件URL（出参拼域名） */
    @MediaUrl
    private String audioUrl;

    /** 音频时长（毫秒，任务侧秒级向上取整）；厂商未返回时长（豆包）或历史数据为 null */
    private Integer durationMs;

    /** 配音文字 */
    private String ttsText;

    /** 配音模型ID */
    private Long voiceModelId;

    /** 音色编码 */
    private String timbreCode;

    /** 是否开启对口型 */
    private Integer enableLipSync;

    /** 任务状态：PROCESSING / SUCCEEDED / FAILED */
    private String status;

    /** 失败原因短文案 */
    private String errorMessage;

    /** 关联音色库ID */
    private Long voiceLibraryId;

    /** 对口型后的最终视频URL（出参拼域名） */
    @MediaUrl
    private String syncVideoUrl;

    /**
     * 对口型任务状态（派生字段，非落库列）：
     * null=未发起；PROCESSING=合成中；SUCCEEDED=已完成（syncVideoUrl 已回填）；FAILED=合成失败（任务侧已退款，可重新发起）。
     * 真实任务状态以 aid_media_task（sync_media_task_id 关联）为权威源，本字段仅为前端轮询便利做的只读投影。
     */
    private String lipSyncStatus;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
