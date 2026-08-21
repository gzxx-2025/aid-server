package com.aid.model.probe.impl;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;

import cn.hutool.core.util.StrUtil;

/**
 * DeepSeek 模型列表只读探测。
 */
@Component
public class DeepSeekProbe extends AbstractReadOnlyProbe {

    private static final String PROVIDER_CODE = "deepseek";
    private static final String MODELS_PATH = "/models";

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        if (Objects.isNull(model) || StrUtil.isBlank(model.getApiSuffix())) {
            return MODELS_PATH;
        }
        String derived = ProbeHttpSupport.deriveSiblingPath(
                model.getApiSuffix(), MODELS_PATH, "/chat/completions", "/responses");
        return StrUtil.blankToDefault(derived, "/");
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (Objects.nonNull(model) && StrUtil.isNotBlank(model.getApiSuffix())
                && Objects.equals("/", resolvePath(model, provider))
                && ProbeHttpSupport.isHttpSuccess(response)) {
            ProbeResult result = ProbeResult.ok("仅网关可达");
            result.setDetail("未验证密钥或模型");
            return result;
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        JSONArray data = Objects.isNull(root) ? null : root.getJSONArray("data");
        String expected = ProbeHttpSupport.resolveModelCode(model);
        if (StrUtil.isBlank(expected) && ProbeHttpSupport.isHttpSuccess(response)
                && Objects.nonNull(data)) {
            return ProbeResult.ok("鉴权查询正常");
        }
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(data) && containsModel(data, expected)) {
            return ProbeResult.ok("模型权限正常");
        }
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(data)) {
            return ProbeResult.fail("模型不可用", ProbeHttpSupport.detail(response));
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private boolean containsModel(JSONArray data, String expected) {
        for (Object item : data) {
            if (item instanceof JSONObject model && Objects.equals(expected, model.getString("id"))) {
                return true;
            }
        }
        return false;
    }
}
