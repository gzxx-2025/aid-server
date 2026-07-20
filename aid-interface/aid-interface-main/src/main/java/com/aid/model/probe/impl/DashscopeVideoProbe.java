package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * DashScope 视频协议探活（protocol = dashscope-video）。
 * DashScope REST 接口，Authorization: Bearer，bad key 返回 401/InvalidApiKey，适用通用空体探活。
 */
@Component
public class DashscopeVideoProbe extends AbstractRestSubmitProbe {

    @Override
    public String protocol() {
        // 与 aid_ai_model.protocol 库表值一致
        return "dashscope-video";
    }
}
