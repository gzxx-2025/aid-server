package com.aid.billing.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费详情分组返回 VO（C 端公共接口）。
 * 按模型大类分别返回各自的计费详情列表：LLM（文本）/ 图片 / 视频 / 配音，
 * 前端可分 Tab 渲染。{@link #creditUnit} 为计费单位（Credits）。
 *
 * @author 视觉AID
 */
@Data
public class BillingDetailGroupVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 计费单位名称（Credits） */
    private String creditUnit;

    /** 文本生成模型（LLM）计费详情 */
    private List<ModelBillingDetailVO> llm = new ArrayList<>();

    /** 图片生成模型计费详情 */
    private List<ModelBillingDetailVO> image = new ArrayList<>();

    /** 视频生成模型计费详情 */
    private List<ModelBillingDetailVO> video = new ArrayList<>();

    /** 配音（TTS）模型计费详情 */
    private List<ModelBillingDetailVO> voice = new ArrayList<>();
}
