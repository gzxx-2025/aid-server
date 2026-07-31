package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询生成记录列表请求DTO
 *
 * @author 视觉AID
 */
@Data
public class GenRecordListRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long storyboardId;

    /**
     * 生成类型(可选): image-图片类(image+grid) / video-分镜视频＝原视频轨(i2v+multi+edge+upload_video，
     * 不含配音视频) / compose-配音视频(一键配音、批量配音合成视频与对口型视频) / 其余值按具体genType精确查询 /
     * 不传则查询所有类型
     */
    private String genType;
}
