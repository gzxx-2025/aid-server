package com.aid.model.probe.impl;

import com.aid.media.constants.ConfigurableAsyncMediaConstants;

import org.springframework.stereotype.Component;

/** 可配置异步图片协议的只读模型探测。 */
@Component
public class ConfigurableAsyncImageProbe extends AbstractConfigurableAsyncMediaProbe {

    @Override
    public String protocol() {
        return ConfigurableAsyncMediaConstants.PROTOCOL_IMAGE;
    }

    @Override
    protected String[] knownSubmitEndings() {
        return new String[]{
                "/v1/images/{operation}/tasks",
                "/v1/images/generations/tasks",
                "/v1/images/edits/tasks"
        };
    }
}
