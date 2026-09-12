package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextOutputLimitResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** DeepSeek 非思考模式的前后缀文本补全。 */
@Slf4j
@Component
public class DeepSeekFimTextProviderClient extends GenericOpenAiCompatibleTextProviderClient {
    private static final Set<String> FIELDS = Set.of("suffix", "max_tokens", "temperature", "top_p", "stop", "echo", "frequency_penalty", "presence_penalty", "logprobs");
    private static final Set<String> REQUEST_FIELDS = Set.of("suffix", "max_tokens", "max_completion_tokens",
            "maxOutputTokens", "temperature", "top_p", "stop", "echo", "frequency_penalty", "presence_penalty", "logprobs");

    @Override
    public String protocol() { return "deepseek:fim"; }

    @Override
    public boolean supportsModel(String modelName) { return false; }

    @Override
    protected boolean supportsTextCompletions() { return true; }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        if (request == null) fail("请输入补全前缀");
        DeepSeekRequestOptions.validate(request.getOptions(), REQUEST_FIELDS);
        if (Boolean.TRUE.equals(request.getReasoningEnabled())
                || request.getMessages() != null && !request.getMessages().isEmpty()) fail("补全输入组合不支持");
        if (request.getPrompt() == null || request.getPrompt().isBlank()) fail("请输入补全前缀");
        if (TextOutputLimitResolver.resolveProviderCap(modelConfig, request.getOptions()) > 4096) fail("补全输出长度超限");
        if (request.getOptions() != null) for (var entry : request.getOptions().entrySet()) {
            if (!DeepSeekRequestOptions.internal(entry.getKey()) && !FIELDS.contains(entry.getKey())
                    && !Set.of("max_completion_tokens", "maxOutputTokens").contains(entry.getKey())) fail("补全参数不支持");
        }
        Object suffix = request.getOptions() == null ? null : request.getOptions().get("suffix");
        if (suffix != null && !(suffix instanceof String)) fail("补全后缀格式错误");
        if (request.getOptions() != null) {
            Map<String, Object> values = request.getOptions();
            DeepSeekRequestOptions.range(values, "temperature", 0, 2, false);
            DeepSeekRequestOptions.range(values, "top_p", Double.MIN_VALUE, 1, false);
            DeepSeekRequestOptions.range(values, "logprobs", 0, 20, true);
            Object echo = values.get("echo");
            if (echo != null && !(echo instanceof Boolean)) fail("补全回显格式错误");
            if (Boolean.TRUE.equals(echo) && (suffix != null || values.get("logprobs") != null)) fail("补全回显组合无效");
        }
    }

    @Override
    protected Map<String, Object> normalizeProviderOptions(AiModelConfigVo config,
            MediaTextGenerateRequest request, Map<String, Object> source) {
        validateRequest(config, request);
        Map<String, Object> options = new LinkedHashMap<>();
        if (source != null) for (String field : FIELDS) if (source.get(field) != null) options.put(field, source.get(field));
        if (source != null && source.get("max_completion_tokens") != null)
            options.put("max_tokens", source.get("max_completion_tokens"));
        options.remove("frequency_penalty");
        options.remove("presence_penalty");
        return options;
    }

    @Override
    protected String buildBody(String model, List<Map<String, Object>> messages, boolean stream,
            Map<String, Object> options, MediaTextGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>(options);
        body.put("model", model);
        body.put("prompt", request.getPrompt());
        body.put("stream", stream);
        if (stream) body.put("stream_options", Map.of("include_usage", true));
        String json = cn.hutool.json.JSONUtil.toJsonStr(body);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48 * 1024 * 1024) fail("请求内容大小超限");
        return json;
    }

    private static void fail(String message) {
        log.info("DeepSeek 补全校验拒绝: {}", message);
        throw new ServiceException(message);
    }
}
