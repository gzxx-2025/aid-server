package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * Agnes 图片协议探活（protocol = agnes-image）。
 * Agnes 为 OpenAI 兼容网关，Authorization: Bearer + REST JSON，适用通用空体探活。
 */
@Component
public class AgnesImageProbe extends AbstractRestSubmitProbe {

    @Override
    public String protocol() {
        // 与 aid_ai_model.protocol 库表值一致
        return "agnes-image";
    }
}
