package com.aid.model.probe.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.model.probe.ProbeResult;

import cn.hutool.core.util.StrUtil;

/**
 * OpenAI 模型元数据只读探测。
 */
@Component
public class OpenAiProviderProbe extends AbstractReadOnlyProbe {

    private static final String PROVIDER_CODE = "openai";
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
        MetadataPaths paths = resolveMetadataPaths(model);
        return StrUtil.isBlank(ProbeHttpSupport.resolveModelCode(model)) ? paths.list() : paths.exact();
    }

    @Override
    protected List<String> resolvePaths(AidAiModel model, AidAiProvider provider) {
        String modelCode = ProbeHttpSupport.resolveModelCode(model);
        MetadataPaths paths = resolveMetadataPaths(model);
        if (StrUtil.isBlank(modelCode)) {
            return List.of(paths.list());
        }
        return Objects.equals(paths.exact(), paths.list())
                ? List.of(paths.list()) : List.of(paths.exact(), paths.list());
    }

    @Override
    protected ProbeResult interpret(AidAiModel model, AidAiProvider provider, ProbeHttpResponse response) {
        return interpretResponse(model, response, false);
    }

    @Override
    protected ProbeResult interpretPath(AidAiModel model, AidAiProvider provider,
                                        String path, ProbeHttpResponse response) {
        MetadataPaths paths = resolveMetadataPaths(model);
        if (paths.gatewayOnly() && ProbeHttpSupport.isHttpSuccess(response)) {
            ProbeResult result = ProbeResult.ok("仅网关可达");
            result.setDetail("未验证密钥或模型");
            return result;
        }
        return interpretResponse(model, response, Objects.equals(paths.list(), path));
    }

    private MetadataPaths resolveMetadataPaths(AidAiModel model) {
        String configured = model == null ? null : model.getApiSuffix();
        String apiRoot = StrUtil.isBlank(configured) ? "/v1"
                : ProbeHttpSupport.deriveSiblingPath(configured, "",
                "/chat/completions", "/responses", "/images/{operation}",
                "/images/generations", "/images/edits");
        if (apiRoot == null) {
            return new MetadataPaths("/", "/", true);
        }
        String list = apiRoot + "/models";
        String modelCode = ProbeHttpSupport.resolveModelCode(model);
        String exact = StrUtil.isBlank(modelCode) ? list
                : list + '/' + ProbeHttpSupport.encodePathSegment(modelCode);
        return new MetadataPaths(exact, list, false);
    }

    private ProbeResult interpretResponse(AidAiModel model, ProbeHttpResponse response,
                                          boolean modelListFallback) {
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        String expected = ProbeHttpSupport.resolveModelCode(model);
        JSONArray data = Objects.isNull(root) ? null : root.getJSONArray("data");
        if (StrUtil.isNotBlank(expected) && ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && Objects.equals(expected, root.getString("id"))) {
            return ProbeResult.ok("模型权限正常");
        }
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(data)) {
            if (StrUtil.isBlank(expected)) {
                return ProbeResult.ok("鉴权查询正常");
            }
            for (Object item : data) {
                if (item instanceof JSONObject itemObject
                        && Objects.equals(expected, itemObject.getString("id"))) {
                    return ProbeResult.ok("模型权限正常");
                }
            }
            if (modelListFallback) {
                ProbeResult result = ProbeResult.ok("仅供应商鉴权正常");
                result.setDetail("未验证当前模型权限");
                return result;
            }
            return ProbeResult.fail("模型不可用", ProbeHttpSupport.detail(response));
        }
        if (ProbeBusinessResponseSupport.isKnownModelMissing(response.body())) {
            return ProbeResult.fail("模型不可用", ProbeHttpSupport.detail(response));
        }
        return ProbeHttpSupport.unexpected(response);
    }

    private record MetadataPaths(String exact, String list, boolean gatewayOnly) {
    }
}
