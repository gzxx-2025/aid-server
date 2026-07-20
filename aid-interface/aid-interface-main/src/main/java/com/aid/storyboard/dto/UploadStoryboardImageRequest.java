package com.aid.storyboard.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 用户上传分镜媒体请求 DTO。
 *
 * @author 视觉AID
 */
@Data
public class UploadStoryboardImageRequest {

    /** 项目ID（必填，用于越权校验） */
    @NotNull(message = "项目不能为空")
    private Long projectId;

    /** 剧集ID（必填，电影项目传0） */
    @NotNull(message = "剧集不能为空")
    private Long episodeId;

    /** 分镜ID（必填，必须存在且归属当前用户） */
    @NotNull(message = "分镜不能为空")
    private Long storyboardId;

    /** 媒体URL（必填，用户已上传到OSS的图片或视频地址，入参自动剥离域名入库） */
    @NotBlank(message = "文件不能为空")
    @MediaUrl
    private String imageUrl;

    /**
     * 媒体类型（选填，image=图片 / video=视频，默认 image，兼容旧调用）。
     * image 归入图片大类可设为主图；video 归入视频大类可设为主视频。
     */
    @Pattern(regexp = "image|video", message = "媒体类型有误")
    private String mediaType = "image";
}
