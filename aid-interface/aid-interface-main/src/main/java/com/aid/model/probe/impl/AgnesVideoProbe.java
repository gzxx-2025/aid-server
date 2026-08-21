package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * Agnes 兼容视频协议的网关可达性回退探测。
 */
@Component
public class AgnesVideoProbe extends AbstractGatewayReachabilityProbe {

    @Override
    public String protocol() {
        return "agnes-video";
    }
}
