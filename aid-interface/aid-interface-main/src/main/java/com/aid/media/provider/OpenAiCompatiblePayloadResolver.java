package com.aid.media.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容协议请求载荷解析工具：。
 *
 * @author 视觉AID
 */
@Slf4j
public final class OpenAiCompatiblePayloadResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    private OpenAiCompatiblePayloadResolver() {
    }

    /**
     * 合并三层请求体附加参数：。
     *
     * @param providerExtraBodyJson 厂商级 JSON 字符串
     * @param modelExtraBodyJson    模型级 JSON 字符串
     * @param requestOptions        调用方运行时 options（可能为空）
     * @return 合并后的 options，可直接传给 {@link TextChatOpenAiPayloadBuilder}
     */
    public static Map<String, Object> mergeExtraBody(String providerExtraBodyJson,
                                                     String modelExtraBodyJson,
                                                     Map<String, Object> requestOptions) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> providerLevel = parseObject(providerExtraBodyJson, "provider extra_body");
        if (providerLevel != null) {
            merged.putAll(providerLevel);
        }
        Map<String, Object> modelLevel = parseObject(modelExtraBodyJson, "model extra_body");
        if (modelLevel != null) {
            merged.putAll(modelLevel);
        }
        if (requestOptions != null && !requestOptions.isEmpty()) {
            merged.putAll(requestOptions);
        }
        return merged.isEmpty() ? null : merged;
    }

    /**
     * 解析自定义 header（aid_ai_provider.extra_headers）。
     * 返回 String→String，便于 HttpRequest 直接附加。
     */
    public static Map<String, String> parseExtraHeaders(String extraHeadersJson) {
        if (StringUtils.isBlank(extraHeadersJson)) {
            return null;
        }
        try {
            Map<String, String> result = MAPPER.readValue(extraHeadersJson, STRING_MAP_TYPE);
            return (result == null || result.isEmpty()) ? null : result;
        } catch (Exception e) {
            log.warn("解析 extra_headers 失败，将忽略：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 base_url + api_suffix 拼成完整 URL，并附加 extra_query（如有）。
     */
    public static String buildApiUrl(String baseUrl, String apiSuffix, String extraQueryJson) {
        if (StringUtils.isBlank(baseUrl) || StringUtils.isBlank(apiSuffix)) {
            return null;
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + apiSuffix;
        if (StringUtils.isBlank(extraQueryJson)) {
            return url;
        }
        Map<String, Object> params = parseObject(extraQueryJson, "provider extra_query");
        if (params == null || params.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), java.nio.charset.StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static Map<String, Object> parseObject(String json, String label) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
            return (map == null || map.isEmpty()) ? null : new HashMap<>(map);
        } catch (Exception e) {
            log.warn("解析 {} 失败，将忽略: {}", label, e.getMessage());
            return null;
        }
    }
}
