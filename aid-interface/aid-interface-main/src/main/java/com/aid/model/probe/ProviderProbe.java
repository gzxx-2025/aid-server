package com.aid.model.probe;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;

/**
 * Provider 探活（连通性测试）能力接口（可选 SPI）。
 */
public interface ProviderProbe {

    /**
     * 本 Probe 支持的协议标识，需与 {@code aid_ai_model.protocol} 库表值一致。
     *
     * @return 协议标识（如 openai-compatible-text）
     */
    String protocol();

    /**
     * 本 Probe 归属的服务商编码（可选）。上层优先按服务商选择只读元数据探测，
     * 未命中时再按模型协议选择安全回退探测。
     * 默认返回 null 表示不参与服务商匹配。
     *
     * @return 服务商编码；默认 null
     */
    default String providerCode() {
        return null;
    }

    /**
     * 判断服务商级探测能否安全使用该模型配置。
     *
     * @param model 候选模型
     * @return 是否支持；默认支持
     */
    default boolean supportsModel(AidAiModel model) {
        return true;
    }

    /**
     * 判断当前探测器是否必须取得具体模型配置。
     *
     * @return 是否必须传入模型；默认可执行供应商级只读查询
     */
    default boolean requiresModel() {
        return false;
    }

    /**
     * 执行探活。
     *
     * @param model    待测模型（取 modelCode / realModelCode / apiSuffix 等）
     * @param provider 所属服务商（取 baseUrl / apiKey / authHeader / authPrefix 等）
     * @return 探活结果
     */
    ProbeResult probe(AidAiModel model, AidAiProvider provider);
}
