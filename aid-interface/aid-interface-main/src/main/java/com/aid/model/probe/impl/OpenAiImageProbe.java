package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容图片协议的网关可达性探测。
 */
@Component
public class OpenAiImageProbe extends AbstractGatewayReachabilityProbe {

    @Override
    public String protocol() {
        return "openai-image";
    }
}
