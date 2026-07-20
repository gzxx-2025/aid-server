package com.aid.model.probe.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.constant.HttpConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 通用「REST 提交端点」探活基类：不只测网关连通，而是向真实提交端点。
 */
@Slf4j
public abstract class AbstractRestSubmitProbe implements ProviderProbe {

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 8000;
    /** detail 响应体截断长度 */
    private static final int DETAIL_BODY_MAX_LEN = 500;
    /** 故意为空的请求体：触发上游参数校验错误，不进入生成流程（零 token） */
    private static final String EMPTY_BODY = "{}";

    /**
     * 默认鉴权前缀（库表 auth_prefix 为空时使用）。子类可覆盖（如 Vidu 用 "Token "）。
     * 默认 {@code Bearer }。
     */
    protected String defaultAuthPrefix() {
        return HttpConstants.AUTH_BEARER_PREFIX;
    }

    /**
     * 解析提交端点后缀。默认取模型 {@code api_suffix}；子类可覆盖为固定路径
     * （如火山 Ark 用固定 {@code /chat/completions} 做密钥探活，不依赖模型 api_suffix）。
     */
    protected String resolveSubmitSuffix(AidAiModel model) {
        return model == null ? null : model.getApiSuffix();
    }

    @Override
    public ProbeResult probe(AidAiModel model, AidAiProvider provider) {
        String baseUrl = provider == null ? null : provider.getBaseUrl();
        if (StrUtil.isBlank(baseUrl)) {
            return ProbeResult.fail("未配置网关地址", "baseUrl 为空");
        }
        String apiKey = provider.getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            return ProbeResult.fail("未配置密钥", "apiKey 为空");
        }
        String apiSuffix = resolveSubmitSuffix(model);
        if (StrUtil.isBlank(apiSuffix)) {
            return ProbeResult.fail("未配置接口后缀", "apiSuffix 为空");
        }
        String url = StrUtil.removeSuffix(baseUrl.trim(), "/")
                + (apiSuffix.startsWith("/") ? apiSuffix.trim() : "/" + apiSuffix.trim());
        String authHeader = StrUtil.isNotBlank(provider.getAuthHeader())
                ? provider.getAuthHeader() : HttpConstants.HEADER_AUTHORIZATION;
        String authPrefix = provider.getAuthPrefix() != null
                ? provider.getAuthPrefix() : defaultAuthPrefix();
        log.info("探活提交, providerCode={}, protocol={}, url={}",
                provider.getProviderCode(), protocol(), url);
        try (HttpResponse response = HttpRequest.post(url)
                .header(authHeader, authPrefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(EMPTY_BODY)
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            int status = response.getStatus();
            String body = response.body();
            String detail = "HTTP " + status + " | " + truncate(body);
            if (status == 401 || status == 403 || isAuthError(body)) {
                log.error("探活鉴权失败, providerCode={}, status={}", provider.getProviderCode(), status);
                return ProbeResult.fail("密钥无效或未授权", detail);
            }
            // 404：端点不存在，多为 api_suffix / base_url 配错（鉴权通过也不算对接成功）
            if (status == 404) {
                log.error("探活端点不存在, providerCode={}, url={}", provider.getProviderCode(), url);
                return ProbeResult.fail("接口地址不存在(检查后缀)", detail);
            }
            // 其它任何响应（参数校验错误 4xx / 2xx）→ 网关可达 + 鉴权通过 + 端点正确
            return ProbeResult.ok("对接正常(接口可达、密钥有效)");
        } catch (Exception e) {
            log.error("探活网关不可达, providerCode={}, err={}", provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 响应体是否命中鉴权类错误（部分网关把鉴权失败包成 200 + 错误码）。 */
    private boolean isAuthError(String body) {
        if (StrUtil.isBlank(body)) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("unauthorized")
                || lower.contains("forbidden")
                || lower.contains("invalid token")
                || lower.contains("invalid api key")
                || lower.contains("invalidapikey")
                || lower.contains("authentication");
    }

    /** 截断响应体，避免明细过长。 */
    private String truncate(String body) {
        if (StrUtil.isBlank(body)) {
            return "";
        }
        return body.length() > DETAIL_BODY_MAX_LEN ? body.substring(0, DETAIL_BODY_MAX_LEN) : body;
    }
}
