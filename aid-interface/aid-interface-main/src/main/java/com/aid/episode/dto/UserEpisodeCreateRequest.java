package com.aid.episode.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户创建剧集请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserEpisodeCreateRequest {

    /** 所属项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 单集标题 */
    @NotBlank(message = "单集标题不能为空")
    @Size(max = 100, message = "单集标题不能超过100个字符")
    private String comicTitle;

    /** 单集描述 */
    @Size(max = 500, message = "单集描述不能超过500个字符")
    private String comicDesc;

    /** 单集封面图（入参自动剥离域名入库，与项目封面同口径） */
    @MediaUrl
    @Size(max = 500, message = "封面地址过长")
    private String comicCoverUrl;

    /** 生成模式(economy经济, performance性能) */
    private String genMode;

    /** 创作模式(i2v图生视频, multi多参生视频) */
    private String creationMode;
}
