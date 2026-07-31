package com.aid.aid.domain.vo;

import java.io.Serializable;

import lombok.Data;

/**
 * 真实模型总览行VO（同一真实模型下的单个展示模型）
 *
 * @author 视觉AID
 */
@Data
public class AidRealModelItemVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private Long id;

    /** 模型展示/选择码（全表唯一） */
    private String modelCode;

    /** 前端展示名称 */
    private String modelName;

    /** 真实上游模型名（空表示回退用 modelCode） */
    private String realModelCode;

    /** 模型分类 (text/image/video/audio) */
    private String modelType;

    /** 生成模式细分 */
    private String generateMode;

    /** 所属服务商ID */
    private Long providerId;

    /** 所属服务商名称 */
    private String providerName;

    /** 状态：0正常 1停用 */
    private String status;

    /** 模型优先级（值越大优先级越高） */
    private Integer priority;
}
