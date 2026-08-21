package com.aid.model.probe.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;

/**
 * 无安全元数据端点时仅验证网关可达性的探测模板。
 */
public abstract class AbstractGatewayReachabilityProbe extends AbstractReadOnlyProbe {

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        return "/";
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeHttpSupport.isHttpSuccess(response)) {
            ProbeResult result = ProbeResult.ok("仅网关可达");
            result.setDetail("未验证密钥或模型");
            return result;
        }
        return ProbeHttpSupport.unexpected(response);
    }
}
