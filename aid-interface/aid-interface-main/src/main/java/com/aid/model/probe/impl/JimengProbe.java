package com.aid.model.probe.impl;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.JimengConstants;
import com.aid.media.provider.volcengine.VolcengineVisualSigner;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;
import com.aid.common.utils.ProviderEndpointUtils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 即梦不存在任务只读查询探测。
 */
@Slf4j
@Component
public class JimengProbe implements ProviderProbe {

    @Override
    public String protocol() {
        return null;
    }

    @Override
    public String providerCode() {
        return JimengConstants.PROVIDER_CODE;
    }

    @Override
    public boolean supportsModel(AidAiModel model) {
        return StrUtil.isNotBlank(resolveReqKey(model));
    }

    @Override
    public boolean requiresModel() {
        return true;
    }

    @Override
    public ProbeResult probe(AidAiModel model, AidAiProvider provider) {
        ProbeResult invalidProvider = ProbeHttpSupport.validateProvider(provider);
        if (Objects.nonNull(invalidProvider)) {
            return invalidProvider;
        }
        if (StrUtil.isBlank(provider.getApiSecret())) {
            return ProbeResult.fail("未配置AK/SK", "apiKey/apiSecret 为空");
        }
        String reqKey = resolveReqKey(model);
        if (StrUtil.isBlank(reqKey)) {
            return ProbeResult.fail("模型不支持", "无法解析模型实际 req_key");
        }
        String signedPath;
        String fullUrl;
        String host;
        try {
            signedPath = resolveSignedPath(model);
            fullUrl = ProviderEndpointUtils.buildSubmitUrl(provider.getBaseUrl(), signedPath);
            host = URI.create(fullUrl).getHost();
        } catch (IllegalArgumentException e) {
            log.error("即梦探测配置无效, providerCode={}, err={}", provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("探测配置无效", e.getMessage());
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put(JimengConstants.QUERY_ACTION, JimengConstants.ACTION_QUERY);
        query.put(JimengConstants.QUERY_VERSION, JimengConstants.API_VERSION);
        fullUrl = fullUrl + '?' + JimengConstants.QUERY_ACTION + '=' + JimengConstants.ACTION_QUERY
                + '&' + JimengConstants.QUERY_VERSION + '=' + JimengConstants.API_VERSION;

        JimengQueryProbeRequest requestBody = new JimengQueryProbeRequest();
        requestBody.setReqKey(reqKey);
        requestBody.setTaskId(ProbeHttpSupport.randomNumericTaskId());
        String jsonBody = JSON.toJSONString(requestBody);

        Map<String, String> signedHeaders;
        try {
            signedHeaders = VolcengineVisualSigner.sign(
                    provider.getApiKey(), provider.getApiSecret(), JimengConstants.REGION,
                    JimengConstants.SERVICE, host, "POST", signedPath, query,
                    JimengConstants.CONTENT_TYPE_JSON, jsonBody);
        } catch (Exception e) {
            log.error("即梦探测签名失败, providerCode={}, err={}", provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("签名失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        HttpRequest request = HttpRequest.post(fullUrl).body(jsonBody);
        for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
            request.header(entry.getKey(), entry.getValue(), true);
        }
        try {
            ProbeHttpResponse response = ProbeHttpSupport.execute(request);
            ProbeResult commonFailure = ProbeHttpSupport.classifyCommonFailure(response);
            if (Objects.nonNull(commonFailure)) {
                return commonFailure;
            }
            JSONObject root = ProbeHttpSupport.parseObject(response.body());
            JSONObject data = Objects.isNull(root) ? null : root.getJSONObject(JimengConstants.RESP_DATA);
            if (ProbeHttpSupport.isHttpSuccess(response) && Objects.nonNull(root) && Objects.nonNull(data)
                    && Objects.equals(JimengConstants.RESP_CODE_SUCCESS,
                    root.getInteger(JimengConstants.RESP_CODE))
                    && Objects.equals(JimengConstants.VENDOR_STATUS_NOT_FOUND,
                    data.getString(JimengConstants.RESP_STATUS))) {
                return ProbeResult.ok("鉴权查询正常");
            }
            return ProbeHttpSupport.unexpected(response);
        } catch (Exception e) {
            log.error("即梦探测网关不可达, providerCode={}, err={}", provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String resolveReqKey(AidAiModel model) {
        if (Objects.isNull(model)) {
            return null;
        }
        String reqKey = lookupReqKey(model.getRealModelCode());
        return StrUtil.isNotBlank(reqKey) ? reqKey : lookupReqKey(model.getModelCode());
    }

    private String lookupReqKey(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return null;
        }
        String normalized = modelCode.trim().toLowerCase(java.util.Locale.ROOT);
        String imageReqKey = JimengConstants.MODEL_CODE_TO_REQ_KEY.get(normalized);
        if (StrUtil.isNotBlank(imageReqKey)) {
            return imageReqKey;
        }
        String videoReqKey = JimengConstants.VIDEO_MODEL_CODE_TO_REQ_KEY.get(normalized);
        if (StrUtil.isNotBlank(videoReqKey)) {
            return videoReqKey;
        }
        if (JimengConstants.MODEL_CODE_TO_REQ_KEY.containsValue(normalized)
                || JimengConstants.VIDEO_MODEL_CODE_TO_REQ_KEY.containsValue(normalized)) {
            return normalized;
        }
        return null;
    }

    private String resolveSignedPath(AidAiModel model) {
        String path = Objects.isNull(model) ? null : model.getApiSuffix();
        String normalized = ProviderEndpointUtils.normalizeSubmitPath(path);
        if (normalized.contains("?")) {
            throw new IllegalArgumentException("即梦路径无效");
        }
        return normalized;
    }
}
