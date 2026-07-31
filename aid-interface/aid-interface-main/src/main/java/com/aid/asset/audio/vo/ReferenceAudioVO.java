package com.aid.asset.audio.vo;

import java.util.Date;

import com.aid.common.aid.oss.annotation.MediaUrl;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 参考音频 VO。
 * {@code durationMs} / {@code audioFormat} 必须回显：出片时按所选模型的
 * capability_json 校验参考音频格式与时长，前端据此提前过滤不可用项，
 * 避免用户选完才在生成环节被拒。
 *
 * @author 视觉AID
 */
@Data
public class ReferenceAudioVO {

    /** 参考音频主键 */
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 所属项目ID */
    private Long projectId;

    /** 所属剧集ID（0=全剧集通用） */
    private Long episodeId;

    /** 音频名称 */
    private String audioName;

    /** 音频地址（@MediaUrl 拼 CDN 域名） */
    @MediaUrl
    private String audioUrl;

    /** 音频时长（毫秒） */
    private Integer durationMs;

    /** 音频格式（wav / mp3） */
    private String audioFormat;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 状态：0启用 1停用 */
    private String status;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
