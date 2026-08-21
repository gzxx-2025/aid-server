package com.aid.model.probe.impl;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.VolcengineConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.common.utils.ProviderEndpointUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 火山方舟任务列表只读探测。
 */
@Component
public class VolcengineProbe extends AbstractReadOnlyProbe {

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return "volcengine";
    }

    @Override
    public boolean supportsModel(AidAiModel model) {
        if (Objects.isNull(model)) {
            return false;
        }
        if (Objects.equals(VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO, model.getProtocol())) {
            return true;
        }
        String modelCode = ProbeHttpSupport.resolveModelCode(model);
        return StrUtil.isNotBlank(modelCode)
                && modelCode.toLowerCase(Locale.ROOT).contains("seedance");
    }

    @Override
    public boolean requiresModel() {
        return true;
    }

    @Override
    protected String resolvePath(AidAiModel model, AidAiProvider provider) {
        return resolveListPath(model, provider);
    }

    @Override
    protected List<String> resolvePaths(AidAiModel model, AidAiProvider provider) {
        String probeTaskId = "cgt-" + java.util.UUID.randomUUID();
        String template = resolveTaskTemplate(model, provider);
        return List.of(resolveListPath(model, provider),
                ProviderEndpointUtils.normalizeTaskQueryTemplate(template).replace("%s", probeTaskId));
    }

    private String resolveTaskTemplate(AidAiModel model, AidAiProvider provider) {
        if (StrUtil.isNotBlank(provider.getTaskQuerySuffix())) {
            return provider.getTaskQuerySuffix();
        }
        return ProviderEndpointUtils.normalizeSubmitPath(model.getApiSuffix()) + "/%s";
    }

    private String resolveListPath(AidAiModel model, AidAiProvider provider) {
        String template = ProviderEndpointUtils.normalizeTaskQueryTemplate(
                resolveTaskTemplate(model, provider));
        String listPath = template.replaceAll("/%s(?:\\?.*)?$", "");
        if (Objects.equals(listPath, template)) {
            listPath = ProviderEndpointUtils.normalizeSubmitPath(model.getApiSuffix());
        }
        return ProbeHttpSupport.addQuery(listPath, "page_num", "1") + "&page_size=1";
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        if (ProbeBusinessResponseSupport.isKnownTaskMissing(response.body())) {
            return ProbeResult.ok("鉴权查询正常");
        }
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && (Objects.nonNull(root.getJSONArray("data")) || Objects.nonNull(root.getJSONArray("items")))) {
            return ProbeResult.ok("鉴权查询正常");
        }
        return ProbeHttpSupport.unexpected(response);
    }
}
