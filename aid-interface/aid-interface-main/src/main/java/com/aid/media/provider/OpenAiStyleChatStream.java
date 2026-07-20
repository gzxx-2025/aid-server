package com.aid.media.provider;

import com.aid.common.constant.HttpConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容 SSE（chat/completions stream）的 HTTP 拉取与增量解析，供多厂商文本 Provider 复用。
 */
@Slf4j
public final class OpenAiStyleChatStream {

    // 单次 HTTP 读流超时上限，避免无限挂起占用线程。
    private static final int HTTP_STREAM_TIMEOUT_MINUTES = 10;

    /**
     * SSE/同步响应体字节上限，防止上游异常返回超长内容导致 OOM；超过即强制终止读取。
     */
    private static final long MAX_STREAM_BODY_BYTES = 50L * 1024L * 1024L;
    /**
     * 错误响应体专用上限：64KB，足以读完任何 HTTP 错误 JSON/HTML，防止错误页面拖累内存。
     */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 全局复用的线程安全 HttpClient，避免每次请求新建连接池/Selector 线程导致资源耗尽。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(HTTP_STREAM_TIMEOUT_MINUTES))
            .build();

    private OpenAiStyleChatStream() {
    }

    /**
     * POST 非流式 Chat Completions（stream=false），一次拿到完整 JSON 响应，稳定解析 usage。
     *
     * @param url          完整请求 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → 默认 Authorization）
     * @param authPrefix   鉴权前缀（null → 默认 "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 不附加）
     * @param jsonBody     请求体
     * @return ProviderSubmitResult 含 directText / rawResponse / usage
     */
    public static ProviderSubmitResult postJsonSync(String url, String apiKey,
                                                    String authHeader, String authPrefix,
                                                    Map<String, String> extraHeaders, String jsonBody) {
        ModelIoDump.req(url, jsonBody); // 【临时调试】记录下发上游入参
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req;
        try {
            req = buildJsonRequest(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, false);
        } catch (IllegalArgumentException badConfig) {
            log.error("非流式文本请求构造失败（鉴权或 URL 非法）, url={}, err={}", url, badConfig.getMessage());
            return ProviderSubmitResult.builder().rawResponse("配置错误").build();
        }
        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("非流式文本请求被中断, url={}", url, e);
            return ProviderSubmitResult.builder().rawResponse("请求被中断").build();
        } catch (IOException e) {
            log.error("非流式文本请求IO异常, url={}", url, e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        String body = resp.body() != null ? resp.body() : "";
        // 同步响应字节上限校验，防止上游异常返回超大 body 导致 OOM。
        if (body.length() > MAX_STREAM_BODY_BYTES) {
            log.error("非流式文本响应超过大小上限, url={}, bodyLen={}, limit={}", url, body.length(), MAX_STREAM_BODY_BYTES);
            return ProviderSubmitResult.builder()
                    .rawResponse("响应超限")
                    .build();
        }
        ModelIoDump.resp(url, body); // 【临时调试】记录上游出参
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("非流式文本上游HTTP失败, url={}, status={}, body={}", url, resp.statusCode(), body);
            return ProviderSubmitResult.builder().rawResponse(body).build();
        }
        // 解析非流式 JSON 响应：choices[0].message.content + usage
        try {
            JsonNode root = MAPPER.readTree(body);
            // 提取文本
            String text = null;
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                text = textOrNull(message.get("content"));
            }
            // 提取 usage
            Map<String, Object> usage = parseUsageFromRoot(root);
            log.info("非流式文本响应解析: url={}, hasText={}, usage={}", url, StringUtils.isNotBlank(text), usage);
            return ProviderSubmitResult.builder()
                .directText(text)
                .rawResponse(truncateRaw(body))
                .usage(usage.isEmpty() ? null : usage)
                .build();
        } catch (Exception e) {
            log.error("非流式文本响应解析失败, url={}, body={}", url, body, e);
            return ProviderSubmitResult.builder()
                .directText(null)
                .rawResponse(truncateRaw(body))
                .build();
        }
    }

    /**
     * 从 OpenAI 兼容响应根节点提取 usage：统一映射 prompt_tokens→input_tokens，completion_tokens→output_tokens。
     */
    static Map<String, Object> parseUsageFromRoot(JsonNode root) {
        Map<String, Object> usage = new HashMap<>();
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return usage;
        }
        if (usageNode.has("prompt_tokens")) {
            int v = usageNode.get("prompt_tokens").asInt();
            usage.put("prompt_tokens", v);
            usage.put("input_tokens", v);
        }
        if (usageNode.has("completion_tokens")) {
            int v = usageNode.get("completion_tokens").asInt();
            usage.put("completion_tokens", v);
            usage.put("output_tokens", v);
        }
        if (usageNode.has("total_tokens")) {
            usage.put("total_tokens", usageNode.get("total_tokens").asInt());
        }
        return usage;
    }

    private static String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > 100_000 ? raw.substring(0, 100_000) + "\n...[truncated]" : raw;
    }

    /**
     * POST 流式 Chat Completions，按行解析 SSE，将正文与思考链增量回调给编排层。
     *
     * @param url          完整请求 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → 默认 Authorization）
     * @param authPrefix   鉴权前缀（null → 默认 "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 不附加）
     * @param jsonBody     请求体
     * @param callbacks    流式回调
     */
    public static void postSseStream(String url, String apiKey,
                                     String authHeader, String authPrefix,
                                     Map<String, String> extraHeaders,
                                     String jsonBody, TextStreamCallbacks callbacks) throws IOException {
        ModelIoDump.req(url, jsonBody); // 【临时调试】记录下发上游入参（流式）
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req;
        try {
            req = buildJsonRequest(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, true);
        } catch (IllegalArgumentException badConfig) {
            log.error("文本流式请求构造失败（鉴权或 URL 非法）, url={}, err={}", url, badConfig.getMessage());
            callbacks.onError("配置错误", badConfig);
            return;
        }
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbacks.onError("请求被中断", e);
            return;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String errBody = readAllAndClose(resp.body());
            log.error("文本流式上游 HTTP 失败, url={}, status={}, body={}", url, resp.statusCode(), errBody);
            // 保留 HTTP 状态码前缀，便于 ErrorNormalizer 归一化
            String hint;
            if (resp.statusCode() == 404) {
                hint = "HTTP 404（请检查 aid_ai_provider.base_url 是否为百炼 OpenAI 兼容基址，例如 https://dashscope.aliyuncs.com/compatible-mode/v1）";
            } else {
                hint = "HTTP " + resp.statusCode();
            }
            String errorDetail = org.apache.commons.lang3.StringUtils.defaultIfBlank(errBody, hint);
            callbacks.onError(errorDetail, null);
            return;
        }
        AtomicBoolean sawDone = new AtomicBoolean(false);
        AtomicBoolean fatal = new AtomicBoolean(false);
        // 累计字节数，超过上限立即终止并抛错
        long totalBytes = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalBytes += (long) line.length() + 1L; // +1 换行符
                if (totalBytes > MAX_STREAM_BODY_BYTES) {
                    log.error("文本流式响应超过大小上限, url={}, totalBytes={}, limit={}",
                            url, totalBytes, MAX_STREAM_BODY_BYTES);
                    callbacks.onError("响应超限", null);
                    fatal.set(true);
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                callbacks.onSseDataLine(data);
                if ("[DONE]".equals(data)) {
                    sawDone.set(true);
                    break;
                }
                if (!emitDeltasFromChunk(data, callbacks)) {
                    fatal.set(true);
                    break;
                }
            }
        }
        if (!fatal.get()) {
            if (!sawDone.get()) {
                log.info("文本流式上游未显式返回[DONE]，按连接结束处理");
            }
            callbacks.onComplete();
        }
    }

    /**
     * @return false 表示解析致命错误，应终止流且不再 onComplete。
     */
    private static boolean emitDeltasFromChunk(String dataJson, TextStreamCallbacks callbacks) {
        if (StringUtils.isBlank(dataJson)) {
            return true;
        }
        try {
            JsonNode root = MAPPER.readTree(dataJson);

            // 先提取 usage（Qwen 等模型的最终 usage chunk 中 choices 为空，
            // 必须在 choices 判断之前解析，否则 usage 会被跳过）。
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                Map<String, Object> usage = parseUsageFromRoot(root);
                if (!usage.isEmpty()) {
                    callbacks.onUsage(usage);
                }
            }

            // 再提取 delta 文本（choices 为空时跳过 delta，但 usage 已处理）。
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).path("delta");
                String content = textOrNull(delta.get("content"));
                if (StringUtils.isNotBlank(content)) {
                    callbacks.onDelta(content);
                }
                // 忽略 reasoning_content（模型思考过程），只保留 content（实际输出）
            }

            return true;
        } catch (Exception e) {
            log.warn("解析 SSE data 片段失败, data={}", dataJson, e);
            callbacks.onError("解析流数据失败", e);
            return false;
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        return n.toString();
    }

    private static String readAllAndClose(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        // 错误响应读取上限 64KB，防止上游返回大 HTML 错误页拖累内存。
        try (InputStream closeable = in;
             BufferedReader br = new BufferedReader(new InputStreamReader(closeable, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            int totalLen = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (totalLen + line.length() + 1 > MAX_ERROR_BODY_BYTES) {
                    sb.append(line, 0, Math.min(line.length(), MAX_ERROR_BODY_BYTES - totalLen));
                    sb.append("\n...[error body truncated]");
                    break;
                }
                sb.append(line).append('\n');
                totalLen += line.length() + 1;
            }
            return sb.toString();
        }
    }

    /**
     * 构建 OpenAI 兼容 Chat Completions HTTP 请求，支持自定义鉴权与额外 header。
     *
     * @param url          完整 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → Authorization）
     * @param authPrefix   鉴权前缀（null → "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 忽略）
     * @param jsonBody     请求体
     * @param accessSse    true 时附加 Accept: text/event-stream
     */
    private static HttpRequest buildJsonRequest(String url, String apiKey,
                                                String authHeader, String authPrefix,
                                                Map<String, String> extraHeaders,
                                                String jsonBody, boolean accessSse) {
        // 鉴权 header 名兜底：默认 Authorization
        String effectiveAuthHeader = StringUtils.isNotBlank(authHeader)
                ? authHeader.trim() : HttpConstants.HEADER_AUTHORIZATION;
        // 鉴权前缀兜底：null 表示用默认 Bearer，空字符串表示显式无前缀
        String effectivePrefix = (authPrefix == null) ? HttpConstants.AUTH_BEARER_PREFIX : authPrefix;
        String authValue = effectivePrefix + StringUtils.defaultString(apiKey);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_STREAM_TIMEOUT_MINUTES))
                .header(effectiveAuthHeader, authValue)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON);
        if (accessSse) {
            builder.header(HttpConstants.HEADER_ACCEPT, HttpConstants.ACCEPT_TEXT_EVENT_STREAM);
        }
        // 附加自定义 header（如 Azure 的 api-version）
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (StringUtils.isBlank(name) || value == null) {
                    continue;
                }
                // 跳过与鉴权/必备 header 同名的项，防止运营误配置覆盖
                if (effectiveAuthHeader.equalsIgnoreCase(name)
                        || HttpConstants.HEADER_CONTENT_TYPE.equalsIgnoreCase(name)
                        || HttpConstants.HEADER_ACCEPT.equalsIgnoreCase(name)) {
                    log.warn("OpenAiStyleChatStream: 忽略与必备 header 冲突的 extra header: {}", name);
                    continue;
                }
                builder.header(name, value);
            }
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
    }
}
