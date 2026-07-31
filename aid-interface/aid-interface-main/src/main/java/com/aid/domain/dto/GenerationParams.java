package com.aid.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 生成参数封装对象
 *
 * 封装前端传入的生成参数：资产引用、摄影参数、文本描述、运镜参数。
 * 校验范围随项目类型变化：精简模式（simple）不校验资产与摄影参数，完整模式（full）校验全部。
 *
 * @author 视觉AID
 */
@Data
public class GenerationParams implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    /** 场景ID，逗号分隔 */
    private String sceneIds;

    /** 角色ID，逗号分隔 */
    private String characterIds;

    /** 道具ID，逗号分隔 */
    private String propIds;

    /** 姿态图ID，逗号分隔 */
    private String poseIds;

    /** 表情图ID，逗号分隔 */
    private String expressionIds;

    /** 特效图ID，逗号分隔 */
    private String effectIds;

    /** 手绘稿ID，逗号分隔 */
    private String sketchIds;
    /** 景别(特写/全景/近景等) */
    private String shotSize;

    /** 拍摄角度(平视/俯拍/第三人称等) */
    private String cameraAngle;

    /** 焦距(50mm等) */
    private String focalLength;

    /** 色彩色调 */
    private String colorTone;

    /** 光线(逆光/顶光等) */
    private String lighting;

    /** 曝光虚化(长曝光/浅景深) */
    private String exposureBlur;
    /** 画面补充文本描述（生图时使用） */
    private String imagePrompt;

    /** 动作描述（生视频时使用） */
    private String videoPrompt;
    /** 运镜(推拉摇移/航拍/360滚动等) */
    private String cameraMovement;

    /** 拍摄手法(希区柯克变焦/延时等) */
    private String shootingTechnique;
}
