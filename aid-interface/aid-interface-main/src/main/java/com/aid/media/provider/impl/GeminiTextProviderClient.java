package com.aid.media.provider.impl;

import com.aid.media.provider.ModelIoDump; // 【临时调试】入参/出参落盘工具

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.GeminiConstants;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.TextStreamCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Google Gemini 文本 Provider：REST :generateContent 非流式（普通 JSON 请求/响应）。
 */
@Slf4j
@Component
public class GeminiTextProviderClient implements TextProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HTTP_TIMEOUT_MINUTES = 10;

    /**
     * 共享 HttpClient 实例，避免每次请求都创建新的连接池/Selector 线程。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
            .build();

    @Override
    public String protocol() {
        return GeminiConstants.PROTOCOL_TEXT;
    }

    @Override
    public boolean supportsModel(String modelName) {
        if (TextProviderClient.super.supportsModel(modelName)) {
            return true;
        }
        String n = StringUtils.defaultString(modelName).toLowerCase();
        return n.contains(GeminiConstants.MODEL_HINT_GEMINI);
    }

    @Override
    public void streamChat(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                           TextStreamCallbacks callbacks) throws IOException {
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            callbacks.onError(GeminiConstants.ERROR_API_KEY_EMPTY, null);
            return;
        }
        String model = resolveEffectiveModel(modelConfig, request);
        // 业务含义：完整 URL = {base_url}{api_suffix}{model}:generateContent，与 Gemini 官方文档示例一致
        String url = buildGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(), model);
        Map<String, Object> body = buildRequestBody(modelConfig, request);
        String json = MAPPER.writeValueAsString(body);
        log.info("Gemini 文本(非流式)提交, url={}, model={}, contentsSize={}", url, model,
                ((List<?>) body.getOrDefault("contents", List.of())).size());
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参

        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
                .header(GeminiConstants.HEADER_API_KEY, apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbacks.onError("请求被中断", e);
            return;
        }
        String respBody = resp.body() != null ? resp.body() : "";
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("Gemini 文本(非流式) HTTP 失败, url={}, status={}, body={}", url, resp.statusCode(), respBody);
            callbacks.onError(StringUtils.defaultIfBlank(respBody, "HTTP " + resp.statusCode()), null);
            return;
        }
        ModelIoDump.resp(url, respBody); // 【临时调试】记录上游出参
        // 业务含义：非流式响应也写一份 raw 进 onSseDataLine，保持 audit/raw_response 落库口径与流式版本一致
        callbacks.onSseDataLine(respBody);
        try {
            parseAndEmit(respBody, callbacks);
        } catch (Exception e) {
            log.error("Gemini 文本(非流式)解析失败, body={}", respBody, e);
            callbacks.onError("解析响应失败", e);
            return;
        }
        callbacks.onComplete();
    }

    /**
     * 非流式文本生成：Gemini 本身已是 :generateContent 非流式，直接复用现有逻辑，
     * 返回 ProviderSubmitResult（含 directText / rawResponse / usage），不再走 streamChat→callback 中转。
     */
    @Override
    public ProviderSubmitResult chatSync(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            return ProviderSubmitResult.builder().rawResponse(GeminiConstants.ERROR_API_KEY_EMPTY).build();
        }
        String model = resolveEffectiveModel(modelConfig, request);
        String url = buildGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(), model);
        Map<String, Object> body = buildRequestBody(modelConfig, request);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return ProviderSubmitResult.builder().rawResponse("JSON序列化失败").build();
        }
        log.info("Gemini 非流式文本(NON_STREAM), url={}, model={}", url, model);
        ModelIoDump.req(url, json); // 【临时调试】记录下发上游入参
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_TIMEOUT_MINUTES))
                .header(GeminiConstants.HEADER_API_KEY, apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderSubmitResult.builder().rawResponse("请求被中断").build();
        } catch (IOException e) {
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        String respBody = resp.body() != null ? resp.body() : "";
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("Gemini chatSync HTTP 失败, status={}, body={}", resp.statusCode(), respBody);
            return ProviderSubmitResult.builder().rawResponse(respBody).build();
        }
        ModelIoDump.resp(url, respBody); // 【临时调试】记录上游出参
        try {
            JsonNode root = MAPPER.readTree(respBody);
            // 解析文本
            StringBuilder fullText = new StringBuilder();
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.path("thought").asBoolean(false)) {
                            continue;
                        }
                        String t = part.path("text").asText(null);
                        if (StringUtils.isNotEmpty(t)) {
                            fullText.append(t);
                        }
                    }
                }
            }
            // 解析 usage
            Map<String, Object> usage = new HashMap<>();
            JsonNode usageNode = root.path("usageMetadata");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                if (usageNode.has("promptTokenCount")) {
                    int v = usageNode.get("promptTokenCount").asInt();
                    usage.put("prompt_tokens", v);
                    usage.put("input_tokens", v);
                }
                if (usageNode.has("candidatesTokenCount")) {
                    int v = usageNode.get("candidatesTokenCount").asInt();
                    usage.put("completion_tokens", v);
                    usage.put("output_tokens", v);
                }
                if (usageNode.has("totalTokenCount")) {
                    usage.put("total_tokens", usageNode.get("totalTokenCount").asInt());
                }
            }
            log.info("Gemini chatSync 响应解析: hasText={}, usage={}", fullText.length() > 0, usage);
            String rawTruncated = respBody.length() > 100_000
                    ? respBody.substring(0, 100_000) + "\n...[truncated]" : respBody;
            return ProviderSubmitResult.builder()
                    .directText(fullText.length() > 0 ? fullText.toString() : null)
                    .rawResponse(rawTruncated)
                    .usage(usage.isEmpty() ? null : usage)
                    .build();
        } catch (Exception e) {
            log.error("Gemini chatSync 响应解析失败, body={}", respBody, e);
            return ProviderSubmitResult.builder().rawResponse(respBody).build();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderTaskResult.builder().status("SUCCEEDED").build();
    }

    /**
     * 解析 Gemini :generateContent 响应：
     *   - candidates[0].content.parts[*].text（跳过 thought=true 思考片段）→ 一次性 onDelta(fullText)
     *   - usageMetadata.{promptTokenCount,candidatesTokenCount,totalTokenCount}
     *     → input_tokens / output_tokens / total_tokens（缺失字段交给统一文本链路兜底）
     */
    private void parseAndEmit(String json, TextStreamCallbacks callbacks) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode candidates = root.path("candidates");
        StringBuilder fullText = new StringBuilder();
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.path("thought").asBoolean(false)) {
                        continue;
                    }
                    String t = part.path("text").asText(null);
                    if (StringUtils.isNotEmpty(t)) {
                        fullText.append(t);
                    }
                }
            }
        }
        if (fullText.length() > 0) {
            callbacks.onDelta(fullText.toString());
        }
        JsonNode usageNode = root.path("usageMetadata");
        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
            Map<String, Object> usage = new HashMap<>();
            if (usageNode.has("promptTokenCount")) {
                usage.put("input_tokens", usageNode.get("promptTokenCount").asInt());
            }
            if (usageNode.has("candidatesTokenCount")) {
                usage.put("output_tokens", usageNode.get("candidatesTokenCount").asInt());
            }
            if (usageNode.has("totalTokenCount")) {
                usage.put("total_tokens", usageNode.get("totalTokenCount").asInt());
            }
            if (!usage.isEmpty()) {
                callbacks.onUsage(usage);
            }
        }
    }

    /**
     * 组装 Gemini 请求体：
     *   - 历史 messages 中 role=system 合并到 systemInstruction
     *   - role=user/assistant 分别映射为 contents[i].role=user/model
     *   - request.prompt 末尾追加为一条 user content
     *   - 默认注入 generationConfig.thinkingConfig.thinkingLevel=low（除非模型 extra_body / 调用方显式指定）
     *   - 合并厂商级/模型级 extra_body 与 options（模型级覆盖厂商级、options 覆盖配置），
     *     使运营可按模型配置 thinking_level（如 Flash 系 minimal、Pro 系 low）
     *   - 模型打标 supportsJsonObject 且请求文本含 JSON 关键词时注入 responseMimeType=application/json
     */
    private Map<String, Object> buildRequestBody(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder systemBuf = new StringBuilder();
        if (request != null && request.getMessages() != null) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item == null || StringUtils.isBlank(item.getContent())) {
                    continue;
                }
                String role = StringUtils.defaultString(item.getRole()).trim().toLowerCase();
                if (Objects.equals("system", role)) {
                    if (systemBuf.length() > 0) {
                        systemBuf.append("\n");
                    }
                    systemBuf.append(item.getContent());
                    continue;
                }
                contents.add(geminiContent(Objects.equals("assistant", role) ? "model" : "user",
                        item.getContent()));
            }
        }
        if (request != null && StringUtils.isNotBlank(request.getPrompt())) {
            contents.add(geminiContent("user", request.getPrompt()));
        }
        // 业务含义：Gemini 强校验 contents 必须非空，否则返回
        // "GenerateContentRequest.contents: contents is not specified"。
        // 上游 validateTextRequest 允许"只有 system 没有 user"的请求通过（qwen/doubao 接受），
        // 这里在 Gemini provider 内做兜底：若拼完仍为空且 system 有内容，
        // 把 system 文本降级为一条 user content 同时不再下发 systemInstruction，避免重复。
        boolean systemDemoted = false;
        if (contents.isEmpty() && systemBuf.length() > 0) {
            contents.add(geminiContent("user", systemBuf.toString()));
            systemDemoted = true;
            log.info("Gemini contents 为空且仅含 system，降级为 user content 避免 400 contents is not specified");
        }
        body.put("contents", contents);
        if (systemBuf.length() > 0 && !systemDemoted) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("parts", List.of(Map.of("text", systemBuf.toString())));
            body.put("systemInstruction", sys);
        }
        Map<String, Object> generationConfig = buildGenerationConfig(modelConfig, request);
        // 结构化输出（JSON Mode）：模型打标且请求文本含 JSON 关键词时注入 responseMimeType，
        // 让上游直接返回标准 JSON，避免 ```json 包裹导致下游解析失败
        com.aid.media.provider.StructuredOutputSupport.applyGeminiJsonModeIfSupported(
                modelConfig, requestTextContainsJsonKeyword(request, systemBuf), generationConfig);
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }
        return body;
    }

    /**
     * 检测本次请求全部文本（system / messages / prompt）是否含 "JSON" 关键词，
     * 作为 JSON Mode 注入的启发条件（要求 JSON 输出的业务提示词均含该词）。
     */
    private boolean requestTextContainsJsonKeyword(MediaTextGenerateRequest request, StringBuilder systemBuf) {
        if (com.aid.media.provider.StructuredOutputSupport.textContainsJsonKeyword(
                systemBuf == null ? null : systemBuf.toString())) {
            return true;
        }
        if (request == null) {
            return false;
        }
        if (com.aid.media.provider.StructuredOutputSupport.textContainsJsonKeyword(request.getPrompt())) {
            return true;
        }
        if (request.getMessages() != null) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item != null && com.aid.media.provider.StructuredOutputSupport
                        .textContainsJsonKeyword(item.getContent())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 组装 generationConfig：厂商级/模型级 extra_body 与 options 合并后白名单透传 +
     * 默认显式下发 thinkingConfig.thinkingLevel=low（模型 extra_body 可按官方档位覆盖，
     * 如 Flash 系配 minimal 贴近"关闭思考"，3.1 Pro 官方最低仅支持 low）。
     */
    private Map<String, Object> buildGenerationConfig(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // 合并优先级：厂商 extra_body < 模型 extra_body < 请求 options
        Map<String, Object> options = com.aid.media.provider.OpenAiCompatiblePayloadResolver.mergeExtraBody(
                modelConfig == null ? null : modelConfig.getExtraBodyJson(),
                modelConfig == null ? null : modelConfig.getModelExtraBodyJson(),
                request != null ? request.getOptions() : null);
        if (options != null) {
            for (String key : new String[]{"temperature", "topP", "topK", "maxOutputTokens",
                    "candidateCount", "stopSequences", "responseMimeType", "responseSchema"}) {
                Object v = options.get(key);
                if (v != null) {
                    generationConfig.put(key, v);
                }
            }
            // 字段名兼容：业务层统一下发 OpenAI 风格的 max_tokens（snake_case）；
            // Gemini 用 maxOutputTokens（camelCase），这里做单向映射。
            // 调用方若同时传两个键，maxOutputTokens 优先（已在上面循环里写入）。
            if (!generationConfig.containsKey("maxOutputTokens")) {
                Object snakeMaxTokens = options.get("max_tokens");
                if (snakeMaxTokens != null) {
                    generationConfig.put("maxOutputTokens", snakeMaxTokens);
                }
            }
        }
        // 思考配置：调用方显式给出则以调用方为准；否则默认下发 thinkingLevel=low，且不开启 includeThoughts
        Object explicitThinking = options == null ? null : options.get("thinkingConfig");
        Object explicitLevel = options == null ? null : options.get("thinking_level");
        if (explicitThinking instanceof Map) {
            generationConfig.put("thinkingConfig", explicitThinking);
        } else if (explicitLevel != null) {
            String levelStr = String.valueOf(explicitLevel).trim().toLowerCase();
            Map<String, Object> tc = new HashMap<>();
            // 业务层 disabled / off / none / 0 全部映射成 Gemini 的 thinkingBudget=0（关闭思考）
            // 其他取值（low / medium / high）直接作为 thinkingLevel 透传
            if ("disabled".equals(levelStr) || "off".equals(levelStr)
                    || "none".equals(levelStr) || "0".equals(levelStr)) {
                tc.put("thinkingBudget", 0);
            } else {
                tc.put("thinkingLevel", String.valueOf(explicitLevel));
            }
            generationConfig.put("thinkingConfig", tc);
        } else {
            Map<String, Object> tc = new HashMap<>();
            tc.put("thinkingLevel", GeminiConstants.DEFAULT_THINKING_LEVEL);
            generationConfig.put("thinkingConfig", tc);
        }
        return generationConfig;
    }

    private static Map<String, Object> geminiContent(String role, String text) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("role", role);
        c.put("parts", List.of(Map.of("text", text)));
        return c;
    }

    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        return GeminiConstants.DEFAULT_TEXT_MODEL;
    }

    /**
     * 构建 URL：{base_url}{api_suffix}{model}:generateContent
     * api_suffix 约定形如 "/v1beta/models/"
     */
    private String buildGenerateContentUrl(String baseUrl, String apiSuffix, String model) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("gemini text model baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StringUtils.isBlank(apiSuffix)) {
            log.error("gemini text model apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = apiSuffix.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        if (!suffix.endsWith("/")) {
            suffix = suffix + "/";
        }
        return base + suffix + model + GeminiConstants.OPERATION_GENERATE_CONTENT;
    }
}
