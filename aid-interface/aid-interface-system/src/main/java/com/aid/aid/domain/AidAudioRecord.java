package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 分镜配音业务记录对象 aid_audio_record
 * 业务层快照：调度与计费走 aid_media_task，本表只存业务侧已发布状态 + 业务字段。
 * status / audio_url 仅在 OSS URL 就绪后才推进 SUCCEEDED，前端直接轮询本表即可。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_audio_record")
public class AidAudioRecord extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 所属项目ID（与 aid_media_task 对齐） */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 所属剧集ID（电影模式为0） */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 所属分镜ID（aid_storyboard.id） */
    @Excel(name = "分镜ID")
    private Long storyboardId;

    /** 音频来源：1=AI文字配音(TTS)，2=用户上传 */
    @Excel(name = "音频来源")
    private Integer audioSource;

    /** TTS 待合成文本（audio_source=1 时使用） */
    @Excel(name = "配音文本")
    private String ttsText;

    /** TTS 模型ID（关联 aid_ai_model.id） */
    @Excel(name = "配音模型ID")
    private Long voiceModelId;

    /** 音色库ID（关联 aid_ai_voice_library.id） */
    @Excel(name = "音色库ID")
    private Long voiceLibraryId;

    /** 厂商侧音色编码 */
    @Excel(name = "音色编码")
    private String timbreCode;

    /** 配音文件 OSS URL（TTS 成功且 OSS 已就绪后回填，或用户上传后直接落库） */
    @Excel(name = "配音文件")
    @MediaUrl
    private String audioUrl;

    /** 音频时长（毫秒） */
    @Excel(name = "音频时长(ms)")
    private Integer durationMs;

    /** 是否对口型：0=否，1=是 */
    @Excel(name = "是否对口型")
    private Integer enableLipSync;

    /** 对口型视频 OSS URL（enable_lip_sync=1 且对口型任务成功后回填） */
    @Excel(name = "对口型视频")
    @MediaUrl
    private String syncVideoUrl;

    /** TTS 媒体任务ID（关联 aid_media_task.id），audio_source=1 时填充 */
    @Excel(name = "TTS任务ID")
    private Long ttsMediaTaskId;

    /** 对口型媒体任务ID（关联 aid_media_task.id），enable_lip_sync=1 时填充 */
    @Excel(name = "对口型任务ID")
    private Long syncMediaTaskId;

    /** 业务侧已发布状态：PROCESSING / SUCCEEDED / FAILED（与 aid_media_task.status 解耦） */
    @Excel(name = "业务状态")
    private String status;

    /** 失败短文案（≤6 字，给用户看） */
    @Excel(name = "失败原因")
    private String errorMessage;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 合成批次号 */
    private String composeBatchId;

}
