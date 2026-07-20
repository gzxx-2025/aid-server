package com.aid.storyboard.dto;

import com.aid.domain.dto.GenerationParams;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起画面生成/抽卡请求DTO
 *
 * @author 视觉AID
 */
@Data
public class GenerateMediaRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long storyboardId;

    /** 生成类型(image单图, grid九宫格, i2v图生视频, multi多参视频, edge首尾视频) */
    @NotBlank(message = "生成类型不能为空")
    private String genType;

    /** 使用的AI模型ID */
    @NotNull(message = "模型ID不能为空")
    private Long modelId;

    /** 用户补充文本描述 */
    private String userInputText;

    /** 图生视频依赖的底图记录ID(genType=i2v时必填) */
    private Long baseImageId;

    /** 首尾帧视频依赖的首图记录ID(genType=edge时必填) */
    private Long firstImageId;

    /** 首尾帧视频依赖的尾图记录ID(genType=edge时必填) */
    private Long lastImageId;

    /** 视频时长(秒) */
    private Integer videoDuration;

    /** 音效描述(无声则不传) */
    private String soundDesc;

    /** 生成参数对象（包含所有资产、摄影、文本等参数） */
    private GenerationParams genParams;
}
