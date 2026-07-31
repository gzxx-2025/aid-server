package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 音频资产对象 aid_audio_asset
 * 存储配音生成「成功」且 OSS URL 已就绪的音频记录，与调度表 {@code aid_media_task}
 * 和业务记录表 {@code aid_audio_record} 解耦，供前端资产库、视频剪辑复用。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_audio_asset")
public class AidAudioAsset extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 分镜ID */
    @Excel(name = "分镜ID")
    private Long storyboardId;

    /** 来源 aid_audio_record.id（唯一） */
    @Excel(name = "配音记录ID")
    private Long audioRecordId;

    /** 来源 aid_media_task.id */
    @Excel(name = "媒体任务ID")
    private Long mediaTaskId;

    /** 音频 OSS 相对路径（出参拼域名） */
    @Excel(name = "音频URL")
    @MediaUrl
    private String audioUrl;

    /** 文件大小（字节） */
    @Excel(name = "文件大小")
    private Long fileSize;

    /** 音频格式：mp3 / wav / pcm */
    @Excel(name = "音频格式")
    private String audioFormat;

    /** 采样率 */
    @Excel(name = "采样率")
    private Integer sampleRate;

    /** 配音文字 */
    @Excel(name = "配音文字")
    private String ttsText;

    /** 音色库ID */
    @Excel(name = "音色库ID")
    private Long voiceLibraryId;

    /** 配音模型ID */
    @Excel(name = "模型ID")
    private Long voiceModelId;

    /** 厂商音色编码 */
    @Excel(name = "音色编码")
    private String voiceCode;

    /** 音色展示名 */
    @Excel(name = "音色名称")
    private String voiceName;

    /** 情感 */
    @Excel(name = "情感")
    private String emotion;

    /** 语速 */
    @Excel(name = "语速")
    private Integer speechRate;

    /** 音量 */
    @Excel(name = "音量")
    private Integer loudnessRate;

    /** 音调 */
    @Excel(name = "音调")
    private Integer pitch;

    /** 来源（1:AI生成 2:用户上传） */
    @Excel(name = "来源")
    private Integer audioSource;

    /** 资产标题 */
    @Excel(name = "标题")
    private String assetTitle;

    /** 删除标志（0:正常 2:删除） */
    private String delFlag;
}
