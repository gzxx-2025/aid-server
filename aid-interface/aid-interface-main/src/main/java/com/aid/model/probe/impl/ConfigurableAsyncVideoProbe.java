package com.aid.model.probe.impl;

import com.aid.media.constants.ConfigurableAsyncMediaConstants;

import org.springframework.stereotype.Component;

/** 可配置异步视频协议的只读模型探测。 */
@Component
public class ConfigurableAsyncVideoProbe extends AbstractConfigurableAsyncMediaProbe {

    @Override
    public String protocol() {
        return ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO;
    }

    @Override
    protected String[] knownSubmitEndings() {
        return new String[]{"/v1/videos"};
    }
}
