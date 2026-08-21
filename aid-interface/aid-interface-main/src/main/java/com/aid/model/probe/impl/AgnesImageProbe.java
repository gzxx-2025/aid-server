package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

/**
 * Agnes 兼容图片协议的网关可达性回退探测。
 */
@Component
public class AgnesImageProbe extends AbstractGatewayReachabilityProbe {

    @Override
    public String protocol() {
        return "agnes-image";
    }
}
