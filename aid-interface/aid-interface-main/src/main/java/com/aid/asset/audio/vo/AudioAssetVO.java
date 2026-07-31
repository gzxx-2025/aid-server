package com.aid.asset.audio.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/**
 * 音频资产 VO（C 端 / 后台共用）
 * C 端按白名单裁剪：不返回 createBy / updateBy / 审计时间等敏感字段；
 * 后台可通过 Service 方法返回完整对象。
 *
 * @author 视觉AID
 */
@Data
public class AudioAssetVO {

    /** 资产主键 */
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 分镜ID */
    private Long storyboardId;

    /** 来源 aid_audio_record.id */
    private Long audioRecordId;

    /** 来源 aid_media_task.id */
    private Long mediaTaskId;

    /** 音频URL（@MediaUrl 拼 CDN 域名） */
    @MediaUrl
    private String audioUrl;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 音频格式 */
    private String audioFormat;

    /** 采样率 */
    private Integer sampleRate;

    /** 配音文字 */
    private String ttsText;

    /** 音色库ID */
    private Long voiceLibraryId;

    /** 配音模型ID */
    private Long voiceModelId;

    /** 厂商音色编码 */
    private String voiceCode;

    /** 音色展示名 */
    private String voiceName;

    /** 情感 */
    private String emotion;

    /** 语速 */
    private Integer speechRate;

    /** 音量 */
    private Integer loudnessRate;

    /** 音调 */
    private Integer pitch;

    /** 来源（1:AI生成 2:用户上传） */
    private Integer audioSource;

    /** 资产标题 */
    private String assetTitle;

    /** 备注（后台可见，C 端可按需返回） */
    private String remark;

    /** 删除标志（仅后台返回） */
    private String delFlag;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间（仅后台返回） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
