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
     * 本 Probe 归属的服务商编码（可选）。当模型 {@code protocol} 取不到专用 Probe 时，
     * 上层按 {@code aid_ai_provider.provider_code} 回退匹配本方法返回值。
     * 用于 protocol 为空、但需按厂商做真实探活的场景（如即梦 SigV4 / 火山 Ark SDK）。
     * 默认返回 null 表示不参与 providerCode 回退匹配。
     *
     * @return 服务商编码；默认 null
     */
    default String providerCode() {
        return null;
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
