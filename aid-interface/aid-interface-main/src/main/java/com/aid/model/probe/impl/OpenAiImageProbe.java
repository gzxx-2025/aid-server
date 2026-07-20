package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * OpenAI 图片协议探活（protocol = openai-image）。
 * OpenAI Images 接口，Authorization: Bearer + REST JSON，适用通用空体探活。
 */
@Component
public class OpenAiImageProbe extends AbstractRestSubmitProbe {

    @Override
    public String protocol() {
        // 与 aid_ai_model.protocol 库表值一致
        return "openai-image";
    }
}
