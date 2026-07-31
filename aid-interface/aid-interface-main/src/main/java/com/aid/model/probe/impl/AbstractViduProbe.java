package com.aid.model.probe.impl;

import com.aid.media.constants.ViduConstants;

/**
 * Vidu 探活基类：复用 {@link AbstractRestSubmitProbe} 的「空体 + 错误码」通用探活，
 * 仅把默认鉴权前缀改为 Vidu 的 {@code Token }（库表 auth_prefix 为空时兜底）。
 */
public abstract class AbstractViduProbe extends AbstractRestSubmitProbe {

    @Override
    protected String defaultAuthPrefix() {
        return ViduConstants.AUTH_TOKEN_PREFIX;
    }
}
