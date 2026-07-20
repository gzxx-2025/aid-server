package com.aid.aid.domain.vo;

import java.io.Serializable;
import java.util.List;

import lombok.Data;

/**
 * 真实模型总览分组VO（按真实上游模型名聚合，组内各模型代码可独立启停）
 *
 * @author 视觉AID
 */
@Data
public class AidRealModelGroupVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 真实上游模型名（real_model_code，空则回退 model_code） */
    private String realModelCode;

    /** 模型分类 (text/image/video/audio) */
    private String modelType;

    /** 启用中的模型数量 */
    private Integer activeCount;

    /** 该真实模型下的模型总数 */
    private Integer totalCount;

    /** 关联模型列表（同真实模型的全部展示模型，含停用） */
    private List<AidRealModelItemVo> models;
}
