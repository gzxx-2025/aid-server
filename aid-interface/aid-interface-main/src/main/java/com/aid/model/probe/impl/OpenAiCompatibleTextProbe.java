package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

import com.aid.media.constants.OpenAiCompatibleConstants;

/**
 * OpenAI 兼容文本协议的网关可达性探测。
 */
@Component
public class OpenAiCompatibleTextProbe extends AbstractGatewayReachabilityProbe {

    @Override
    public String protocol() {
        return OpenAiCompatibleConstants.PROTOCOL_TEXT;
    }
}
