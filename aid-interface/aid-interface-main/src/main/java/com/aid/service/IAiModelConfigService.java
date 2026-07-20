package com.aid.service;

import com.aid.domain.vo.AiModelConfigVo;

/**
 * AI模型配置聚合Service接口。
 */
public interface IAiModelConfigService {

    /**
     * 按模型代码查询
     *
     * @param modelCode 模型真实调用代码 (如: qwen-image-max)
     * @return 已组装的模型配置（含服务商信息和用户覆盖），不存在返回 null
     */
    AiModelConfigVo selectByModelCode(String modelCode);

    /**
     * 按模型分类查询优先级最高的模型。
     *
     * @param category 模型分类 (image/video/audio)
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectByCategoryWithHighestPriority(String category);

    /**
     * 按分类查询低于指定优先级的最高优先级模型。
     *
     * @param category         模型分类
     * @param currentPriority  当前模型优先级
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectFallbackByCategoryAndLessPriority(String category, Integer currentPriority);

    /**
     * 按模型ID查询
     *
     * @param modelId 模型主键
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectByModelId(Long modelId);
}
