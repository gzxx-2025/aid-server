package com.aid.model.probe.impl;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.KlingConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;

/**
 * 可灵外部任务标识只读查询探测。
 */
@Component
public class KlingProbe extends AbstractReadOnlyProbe {

    private static final int BUSINESS_OK = 0;

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return KlingConstants.PROVIDER_CODE;
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        return ProviderEndpointUtils.normalizeTaskQueryTemplate(provider.getTaskQuerySuffix())
                .replace("%s", ProbeHttpSupport.randomProbeId());
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeBusinessResponseSupport.isKnownTaskMissing(response.body())
                || ProbeBusinessResponseSupport.isKlingParameterValidation(response.body())) {
            return ProbeResult.ok("鉴权查询正常");
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        JSONArray data = Objects.isNull(root) ? null : root.getJSONArray("data");
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && Objects.equals(BUSINESS_OK, root.getInteger("code")) && Objects.nonNull(data)) {
            return ProbeResult.ok("鉴权查询正常");
        }
        return ProbeHttpSupport.unexpected(response);
    }
}
