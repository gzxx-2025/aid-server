package com.aid.billing.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 计费详情查询请求 DTO（C 端公共接口）。
 * 用于查询当前正在运行（状态正常 + 供应商正常）的各类 AI 模型的计费规则详情。
 * 所有字段均为可选：不传则返回全部分类（LLM / 图片 / 视频 / 配音）的计费详情。
 *
 * @author 视觉AID
 */
@Data
public class BillingDetailRequest implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模型大类（可选）：text-文本/LLM、image-图片、video-视频、audio-配音。
     * 传入后仅返回该分类的计费详情，其它分类为空数组；不传返回全部分类。
     */
    private String modelType;

    /**
     * 模型名称关键字（可选）：按模型展示名称模糊搜索（同时兼容模型展示码）。
     * 不传则返回该分类下全部模型。
     */
    private String modelName;
}
