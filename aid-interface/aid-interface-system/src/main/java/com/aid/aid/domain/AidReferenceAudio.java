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
 * 用户上传参考音频对象 aid_reference_audio。
 * 参考音频的第三条来源：区别于角色音色试听样音（隐式）与系统 TTS 配音记录（显式），
 * 本表存用户自行上传的音频文件，按用户 + 项目隔离。
 * 时长在上传时一次探测并落库，出片时直接取用，不再重复下载。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_reference_audio")
public class AidReferenceAudio extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 所属项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 所属剧集ID（0=全剧集通用） */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 音频名称（用户命名，列表选择用） */
    @Excel(name = "音频名称")
    private String audioName;

    /** 音频文件 URL（存相对路径，下发前拼域名） */
    @MediaUrl
    @Excel(name = "音频地址")
    private String audioUrl;

    /** 音频时长（毫秒），上传时探测 */
    @Excel(name = "时长毫秒")
    private Integer durationMs;

    /** 音频格式（wav / mp3） */
    @Excel(name = "音频格式")
    private String audioFormat;

    /** 文件大小（字节） */
    @Excel(name = "文件大小")
    private Long fileSize;

    /** 状态：0启用 1停用 */
    @Excel(name = "状态")
    private String status;

    /** 删除标志：0存在 2删除 */
    private String delFlag;
}
