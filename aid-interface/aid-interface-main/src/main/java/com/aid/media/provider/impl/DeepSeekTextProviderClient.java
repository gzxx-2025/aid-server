package com.aid.media.provider.impl;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.tokendance.provider.text.TokenDanceToolMessages;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** DeepSeek 官方多模态对话与思考工具续轮适配。 */
@Slf4j
@Component
public class DeepSeekTextProviderClient extends GenericOpenAiCompatibleTextProviderClient {
    public static final String PROTOCOL = "deepseek:chat-completions";
    private static final Set<String> DETAILS = Set.of("auto", "low", "high", "original");
    private static final Set<String> FIELDS = Set.of("max_tokens", "max_completion_tokens", "maxOutputTokens",
            "temperature", "top_p", "stop", "tools", "tool_choice", "response_format", "logprobs",
            "top_logprobs", "user_id", "frequency_penalty", "presence_penalty");

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public boolean supportsModel(String modelName) {
        return false;
    }

    @Override
    protected boolean supportsToolMessages() {
        return true;
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        if (request == null) throw reject("请输入对话内容");
        TokenDanceToolMessages.validate(modelConfig, request);
        Map<String, Object> options = request.getOptions();
        DeepSeekRequestOptions.validate(options, FIELDS);
        if (options != null) {
            Object format = options.get("response_format");
            if (format != null && (!(format instanceof Map<?, ?> value)
                    || value.size() != 1 || value.get("type") == null || !Set.of("text", "json_object").contains(value.get("type"))))
                throw reject("输出格式不支持");
            Object choice = options.get("tool_choice");
            if (choice != null && !(choice instanceof Map<?, ?>)
                    && !Set.of("auto", "none", "required").contains(choice)) throw reject("工具选择无效");
            if (choice instanceof Map<?, ?> value
                    && (!"function".equals(value.get("type")) || !(value.get("function") instanceof Map<?, ?> function)
                    || !(function.get("name") instanceof String))) throw reject("工具选择无效");
            if (!Boolean.FALSE.equals(request.getReasoningEnabled())
                    && ("required".equals(choice) || choice instanceof Map<?, ?>)) throw reject("思考不支持强制工具");
            if (choice instanceof Map<?, ?> value) {
                Object name = ((Map<?, ?>) value.get("function")).get("name");
                if (!(options.get("tools") instanceof java.util.Collection<?> definitions)
                        || definitions.stream().noneMatch(item -> item instanceof Map<?, ?> tool
                        && tool.get("function") instanceof Map<?, ?> function && name.equals(function.get("name"))))
                    throw reject("指定工具不存在");
            }
        }
        boolean tools = options != null && options.get("tools") instanceof java.util.Collection<?> values && !values.isEmpty();
        if (request.getMessages() == null) return;
        for (var message : request.getMessages()) {
            if (message == null) continue;
            String role = message.getRole() == null ? "user" : message.getRole().trim().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("system", "user", "assistant", "tool").contains(role)) throw reject("消息角色不支持");
            message.setRole(role);
            if (Boolean.TRUE.equals(message.getPrefix())
                    && (modelConfig.getApiSuffix() == null || !modelConfig.getApiSuffix().contains("/beta/"))
                    && (modelConfig.getBaseUrl() == null || !modelConfig.getBaseUrl().endsWith("/beta"))) {
                throw reject("前缀需要补全协议");
            }
            if (tools && !Boolean.FALSE.equals(request.getReasoningEnabled())
                    && "assistant".equals(message.getRole()) && message.getReasoningContent() == null) {
                throw reject("工具思考上下文缺失");
            }
            if (message.getParts() == null) continue;
            for (var part : message.getParts()) {
                if (part == null || !"image".equalsIgnoreCase(part.getType())) continue;
                if (part.getDetail() != null && !DETAILS.contains(part.getDetail())) throw reject("图片精度不支持");
                if (part.getUrl() != null && part.getUrl().length() > 8192) throw reject("媒体地址长度超限");
            }
        }
    }

    @Override
    protected Map<String, Object> normalizeProviderOptions(AiModelConfigVo modelConfig,
            MediaTextGenerateRequest request, Map<String, Object> source) {
        validateRequest(modelConfig, request);
        Map<String, Object> options = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        options.keySet().removeIf(key -> DeepSeekRequestOptions.internal(key));
        if (options.containsKey("max_completion_tokens"))
            options.put("max_tokens", options.remove("max_completion_tokens"));
        options.remove("frequency_penalty");
        options.remove("presence_penalty");
        if (TextReasoningOptionsResolver.isDeepSeekFlash(modelConfig)) {
            boolean thinking = options.get("thinking") instanceof Map<?, ?> value && "enabled".equals(value.get("type"));
            if (thinking) {
                options.remove("temperature");
                options.remove("presence_penalty");
                options.remove("frequency_penalty");
                if (options.get("top_p") instanceof Number value && value.doubleValue() < 0.95D) options.put("top_p", 0.95D);
            } else {
                options.remove("top_p");
            }
        }
        return options;
    }

    @Override
    protected String buildBody(String model, java.util.List<Map<String, Object>> messages, boolean stream,
            Map<String, Object> options, MediaTextGenerateRequest request) {
        // 媒体探测元数据不属于 DeepSeek image_url 的协议字段。
        for (Map<String, Object> message : messages) if (message.get("content") instanceof java.util.List<?> parts)
            for (Object part : parts) if (part instanceof Map<?, ?> item && item.get("image_url") instanceof Map<?, ?> source) {
                source.remove("mime_type");
                source.remove("fps");
            }
        String body = super.buildBody(model, messages, stream, options, request);
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 48 * 1024 * 1024)
            throw reject("请求内容大小超限");
        return body;
    }

    private static ServiceException reject(String message) {
        log.info("DeepSeek 请求校验拒绝: {}", message);
        return new ServiceException(message);
    }
}
