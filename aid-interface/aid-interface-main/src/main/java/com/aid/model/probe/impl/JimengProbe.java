package com.aid.model.probe.impl;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.media.constants.JimengConstants;
import com.aid.media.provider.volcengine.VolcengineVisualSigner;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 即梦（火山视觉）探活（按 provider_code = jimeng 回退匹配）。
 */
@Slf4j
@Component
public class JimengProbe implements ProviderProbe {

    private static final int HTTP_TIMEOUT_MS = 8000;
    private static final int DETAIL_BODY_MAX_LEN = 500;
    /** 空体：AK/SK 有效则触发参数错误，不进入生成 */
    private static final String EMPTY_BODY = "{}";

    @Override
    public String protocol() {
        // 不按 protocol 匹配（即梦图片 protocol 为空），仅按 providerCode 回退匹配
        return null;
    }

    @Override
    public String providerCode() {
        return JimengConstants.PROVIDER_CODE;
    }

    @Override
    public ProbeResult probe(AidAiModel model, AidAiProvider provider) {
        if (provider == null || StrUtil.isBlank(provider.getApiKey()) || StrUtil.isBlank(provider.getApiSecret())) {
            return ProbeResult.fail("未配置 AK/SK", "apiKey/apiSecret 为空");
        }
        String baseUrl = StrUtil.blankToDefault(provider.getBaseUrl(), JimengConstants.DEFAULT_BASE_URL).trim();
        baseUrl = StrUtil.removeSuffix(baseUrl, "/");
        String host = parseHostOrDefault(baseUrl);
        Map<String, String> query = new LinkedHashMap<>();
        query.put(JimengConstants.QUERY_ACTION, JimengConstants.ACTION_SUBMIT);
        query.put(JimengConstants.QUERY_VERSION, JimengConstants.API_VERSION);
        String fullUrl = baseUrl + "/?" + JimengConstants.QUERY_ACTION + '=' + JimengConstants.ACTION_SUBMIT
                + '&' + JimengConstants.QUERY_VERSION + '=' + JimengConstants.API_VERSION;
        Map<String, String> signedHeaders;
        try {
            signedHeaders = VolcengineVisualSigner.sign(
                    provider.getApiKey(), provider.getApiSecret(),
                    JimengConstants.REGION, JimengConstants.SERVICE, host,
                    "POST", "/", query, JimengConstants.CONTENT_TYPE_JSON, EMPTY_BODY);
        } catch (Exception e) {
            return ProbeResult.fail("签名失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        log.info("即梦探活提交, providerCode={}, url={}", provider.getProviderCode(), fullUrl);
        HttpRequest post = HttpRequest.post(fullUrl).body(EMPTY_BODY).timeout(HTTP_TIMEOUT_MS);
        for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
            post.header(entry.getKey(), entry.getValue(), true);
        }
        try (HttpResponse response = post.execute()) {
            int status = response.getStatus();
            String body = response.body();
            String detail = "HTTP " + status + " | " + truncate(body);
            if (status == 401 || status == 403 || isAuthError(body)) {
                log.error("即梦探活鉴权失败, providerCode={}, status={}", provider.getProviderCode(), status);
                return ProbeResult.fail("密钥无效或未授权", detail);
            }
            return ProbeResult.ok("对接正常(接口可达、密钥有效)");
        } catch (Exception e) {
            log.error("即梦探活网关不可达, providerCode={}, err={}", provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 从 baseUrl 解析 host，失败回退默认。 */
    private String parseHostOrDefault(String baseUrl) {
        try {
            String h = URI.create(baseUrl).getHost();
            return StrUtil.isNotBlank(h) ? h : JimengConstants.DEFAULT_HOST;
        } catch (Exception e) {
            return JimengConstants.DEFAULT_HOST;
        }
    }

    /** 火山视觉鉴权失败的典型错误码/关键字（SigV4 校验失败、AK 无效、无权限等）。 */
    private boolean isAuthError(String body) {
        if (StrUtil.isBlank(body)) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("signaturedoesnotmatch")
                || lower.contains("invalidaccesskey")
                || lower.contains("accessdenied")
                || lower.contains("authfailure")
                || lower.contains("invalidcredential")
                || lower.contains("invalidauthorization")
                || lower.contains("missingauthentication")
                || lower.contains("unauthorized")
                || lower.contains("forbidden")
                || lower.contains("no permission")
                || lower.contains("nopermission");
    }

    /** 截断响应体，避免明细过长。 */
    private String truncate(String body) {
        if (StrUtil.isBlank(body)) {
            return "";
        }
        return body.length() > DETAIL_BODY_MAX_LEN ? body.substring(0, DETAIL_BODY_MAX_LEN) : body;
    }
}
