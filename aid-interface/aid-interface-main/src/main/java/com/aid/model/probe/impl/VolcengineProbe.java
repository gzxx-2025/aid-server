package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidAiModel;

/**
 * 火山方舟（Ark）探活（按 provider_code = volcengine 回退匹配）。
 * Seedream 图片 / Seedance 视频走 Ark Java SDK 提交，无法直接发「空体 HTTP」，但其 base_url
 * （ark.cn-beijing.volces.com/api/v3）是 OpenAI 兼容、Bearer 鉴权的 REST 网关。
 * 因此用固定的 {@code /chat/completions} 端点发空体做密钥+网关探活：
 * 密钥无效→401/403；密钥有效→400 参数错误（不进入生成，零 token）。
 * 不依赖模型 api_suffix（其值为 SDK:xxx，非真实路径），不触碰任何业务提交代码。
 */
@Component
public class VolcengineProbe extends AbstractRestSubmitProbe {

    /** Ark OpenAI 兼容鉴权探活端点（固定） */
    private static final String ARK_CHAT_COMPLETIONS = "/chat/completions";

    @Override
    public String protocol() {
        // 不按 protocol 匹配（Seedream/Seedance 模型 protocol 为空），仅按 providerCode 回退匹配
        return null;
    }

    @Override
    public String providerCode() {
        // 与 aid_ai_provider.provider_code 一致
        return "volcengine";
    }

    @Override
    protected String resolveSubmitSuffix(AidAiModel model) {
        // 固定走 Ark 的 chat/completions 做密钥探活，不用模型 api_suffix(SDK:xxx)
        return ARK_CHAT_COMPLETIONS;
    }
}
