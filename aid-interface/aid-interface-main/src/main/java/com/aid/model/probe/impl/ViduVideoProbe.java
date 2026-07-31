package com.aid.model.probe.impl;

import org.springframework.stereotype.Component;

import com.aid.media.constants.ViduConstants;

/**
 * Vidu 视频协议探活（protocol = vidu-video）：复用 {@link AbstractViduProbe} 的空体错误码探活。
 */
@Component
public class ViduVideoProbe extends AbstractViduProbe {

    @Override
    public String protocol() {
        return ViduConstants.PROTOCOL_VIDEO;
    }
}
