package com.aid.model.probe.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.common.constant.HttpConstants;
import com.aid.media.constants.OpenAiCompatibleConstants;
import com.aid.model.probe.ProbeResult;
import com.aid.model.probe.ProviderProbe;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容文本协议探活实现（protocol = openai-compatible-text）。
 */
@Slf4j
@Component
public class OpenAiCompatibleTextProbe implements ProviderProbe {

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 8000;

    /** 探活默认路径后缀（模型未配置 apiSuffix 时兜底） */
    private static final String DEFAULT_CHAT_SUFFIX = "/chat/completions";

    /** 探活固定提示词（绝不真生成内容） */
    private static final String PING_CONTENT = "ping";

    /** 探活最大输出 token 数（尽量小，趋近零成本） */
    private static final int PING_MAX_TOKENS = 5;

    /** detail 中响应体最大截断长度，避免日志/明细过长 */
    private static final int DETAIL_BODY_MAX_LEN = 500;

    @Override
    public String protocol() {
        // 与 aid_ai_model.protocol 库表值一致
        return OpenAiCompatibleConstants.PROTOCOL_TEXT;
    }

    @Override
    public ProbeResult probe(AidAiModel model, AidAiProvider provider) {
        String baseUrl = provider == null ? null : provider.getBaseUrl();
        if (StrUtil.isBlank(baseUrl)) {
            log.error("文本探活失败: baseUrl 为空, providerCode={}",
                    provider == null ? null : provider.getProviderCode());
            return ProbeResult.fail("未配置网关地址", "baseUrl 为空");
        }
        String apiKey = provider.getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            log.error("文本探活失败: apiKey 为空, providerCode={}", provider.getProviderCode());
            return ProbeResult.fail("未配置密钥", "apiKey 为空");
        }

        String url = buildProbeUrl(baseUrl, model == null ? null : model.getApiSuffix());

        String authHeader = StrUtil.isNotBlank(provider.getAuthHeader())
                ? provider.getAuthHeader() : HttpConstants.HEADER_AUTHORIZATION;
        String authPrefix = provider.getAuthPrefix() != null
                ? provider.getAuthPrefix() : HttpConstants.AUTH_BEARER_PREFIX;

        String upstreamModel = resolveUpstreamModel(model);

        String body = buildPingBody(upstreamModel);

        log.info("文本探活提交, providerCode={}, model={}, url={}",
                provider.getProviderCode(), upstreamModel, url);
        try (HttpResponse response = HttpRequest.post(url)
                .header(authHeader, authPrefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(body)
                .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .execute()) {
            int status = response.getStatus();
            String detail = "HTTP " + status + " | " + truncate(response.body());
            if (status >= 200 && status < 300) {
                // 2xx：凭证 + 网关均连通
                return ProbeResult.ok("连通正常");
            }
            if (status == 401 || status == 403) {
                // 鉴权失败：凭证无效或无权限
                log.error("文本探活凭证错误, providerCode={}, status={}", provider.getProviderCode(), status);
                return ProbeResult.fail("密钥无效或无权限", detail);
            }
            // 其它状态码：到达网关但返回异常
            log.error("文本探活网关异常, providerCode={}, status={}", provider.getProviderCode(), status);
            return ProbeResult.fail("网关返回异常", detail);
        } catch (Exception e) {
            // 连接被拒 / 超时 / DNS 等：网关不可达。异常前已 log，明细不含密钥
            log.error("文本探活网关不可达, providerCode={}, err={}",
                    provider.getProviderCode(), e.getMessage());
            return ProbeResult.fail("网关连接失败", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 拼装探活 URL：baseUrl 去掉结尾斜杠后拼 apiSuffix（缺省 /chat/completions）。
     */
    private String buildProbeUrl(String baseUrl, String apiSuffix) {
        String base = StrUtil.removeSuffix(baseUrl.trim(), "/");
        String suffix = StrUtil.isNotBlank(apiSuffix) ? apiSuffix.trim() : DEFAULT_CHAT_SUFFIX;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    /**
     * 解析上游真实模型名：realModelCode 优先，回退 modelCode。
     */
    private String resolveUpstreamModel(AidAiModel model) {
        if (model == null) {
            return OpenAiCompatibleConstants.DEFAULT_TEXT_MODEL;
        }
        if (StrUtil.isNotBlank(model.getRealModelCode())) {
            return model.getRealModelCode();
        }
        if (StrUtil.isNotBlank(model.getModelCode())) {
            return model.getModelCode();
        }
        return OpenAiCompatibleConstants.DEFAULT_TEXT_MODEL;
    }

    /**
     * 构造最小 chat/completions 请求体。
     */
    private String buildPingBody(String upstreamModel) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", PING_CONTENT);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", upstreamModel);
        payload.put("messages", messages);
        payload.put("max_tokens", PING_MAX_TOKENS);
        return JSON.toJSONString(payload);
    }

    /**
     * 截断响应体，避免明细过长。
     */
    private String truncate(String body) {
        if (StrUtil.isBlank(body)) {
            return "";
        }
        return body.length() > DETAIL_BODY_MAX_LEN ? body.substring(0, DETAIL_BODY_MAX_LEN) : body;
    }
}
