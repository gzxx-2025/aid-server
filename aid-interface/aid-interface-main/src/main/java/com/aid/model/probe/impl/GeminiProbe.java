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
import cn.hutool.http.HttpRequest;

/**
 * Gemini 模型元数据只读探测。
 */
@Component
public class GeminiProbe extends AbstractReadOnlyProbe {

    private static final String PROVIDER_CODE = "gemini";
    private static final String DEFAULT_TEMPLATE = "/v1beta/models/{model}:generateContent";
    private static final String MODEL_PLACEHOLDER = "{model}";
    private static final String GENERATE_OPERATION = ":generateContent";
    private static final String API_KEY_HEADER = "x-goog-api-key";

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
        String modelCode = normalizeModelCode(ProbeHttpSupport.resolveModelCode(model));
        MetadataPaths paths = resolveMetadataPaths(model);
        return StrUtil.isBlank(modelCode) ? paths.list() : paths.exact();
    }

    @Override
    protected List<String> resolvePaths(AidAiModel model, AidAiProvider provider) {
        String modelCode = normalizeModelCode(ProbeHttpSupport.resolveModelCode(model));
        MetadataPaths paths = resolveMetadataPaths(model);
        if (StrUtil.isBlank(modelCode)) {
            return List.of(paths.list());
        }
        return Objects.equals(paths.exact(), paths.list())
                ? List.of(paths.list()) : List.of(paths.exact(), paths.list());
    }

    @Override
    protected void applyAuth(HttpRequest request, AidAiProvider provider) {
        request.header(API_KEY_HEADER, provider.getApiKey(), true);
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
        String configured = Objects.isNull(model) || StrUtil.isBlank(model.getApiSuffix())
                ? DEFAULT_TEMPLATE : model.getApiSuffix();
        String normalized = com.aid.common.utils.ProviderEndpointUtils.normalizeSubmitPath(configured);
        String path = normalized.split("\\?", 2)[0];
        String modelCode = normalizeModelCode(ProbeHttpSupport.resolveModelCode(model));
        String encodedModel = ProbeHttpSupport.encodePathSegment(modelCode);
        String exact;
        String list;
        int placeholderIndex = path.indexOf(MODEL_PLACEHOLDER);
        if (placeholderIndex >= 0 && placeholderIndex == path.lastIndexOf(MODEL_PLACEHOLDER)) {
            String metadataTemplate = stripGenerateOperation(path);
            exact = StrUtil.isBlank(modelCode) ? metadataTemplate
                    : metadataTemplate.replace(MODEL_PLACEHOLDER, encodedModel);
            list = metadataTemplate.substring(0, metadataTemplate.indexOf(MODEL_PLACEHOLDER));
            list = StrUtil.removeSuffix(list, "/");
        } else if (placeholderIndex < 0 && path.endsWith("/")) {
            list = StrUtil.removeSuffix(path, "/");
            exact = StrUtil.isBlank(modelCode) ? list : path + encodedModel;
        } else if (placeholderIndex < 0 && path.endsWith(GENERATE_OPERATION)) {
            exact = stripGenerateOperation(path);
            int slash = exact.lastIndexOf('/');
            if (slash <= 0) {
                return new MetadataPaths("/", "/", true);
            }
            list = exact.substring(0, slash);
        } else {
            return new MetadataPaths("/", "/", true);
        }
        return new MetadataPaths(exact, ProbeHttpSupport.addQuery(list, "pageSize", "1"), false);
    }

    private String stripGenerateOperation(String path) {
        return path.endsWith(GENERATE_OPERATION)
                ? path.substring(0, path.length() - GENERATE_OPERATION.length()) : path;
    }

    private ProbeResult interpretResponse(AidAiModel model, ProbeHttpResponse response,
                                          boolean modelListFallback) {
        JSONObject root = ProbeHttpSupport.parseObject(response.body());
        String modelCode = normalizeModelCode(ProbeHttpSupport.resolveModelCode(model));
        JSONArray models = Objects.isNull(root) ? null : root.getJSONArray("models");
        String expected = "models/" + modelCode;
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root)
                && Objects.equals(expected, root.getString("name"))) {
            return ProbeResult.ok("模型权限正常");
        }
        if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(models)) {
            if (StrUtil.isBlank(modelCode)) {
                return ProbeResult.ok("鉴权查询正常");
            }
            for (Object item : models) {
                if (item instanceof JSONObject itemObject
                        && Objects.equals(expected, itemObject.getString("name"))) {
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

    private String normalizeModelCode(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return modelCode;
        }
        String normalized = modelCode.trim();
        return normalized.startsWith("models/") ? normalized.substring("models/".length()) : normalized;
    }

    private record MetadataPaths(String exact, String list, boolean gatewayOnly) {
    }
}
