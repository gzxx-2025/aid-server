package com.aid.tokendance.provider.text;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.StructuredOutputSupport;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.provider.TextStreamCallbacks;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TokenDance Anthropic Messages 原生协议。 */
@Component
public class TokenDanceAnthropicMessagesTextProviderClient extends AbstractTokenDanceTextProviderClient
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAFE_CONFIG_DEFAULTS = Set.of(
            "temperature", "top_p", "top_k", "stop_sequences", "metadata");
    private static final Set<String> OPTION_KEYS = Set.of(
            "temperature", "top_p", "top_k", "stop_sequences", "tools", "tool_choice", "metadata",
            "thinking", "output_config");
    private static final Set<String> REQUEST_OPTION_KEYS = Set.of(
            "temperature", "top_p", "top_k", "stop_sequences", "tools", "tool_choice", "metadata",
            TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY,
            TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
            TextReasoningOptionsResolver.ENABLED_KEY, TextReasoningOptionsResolver.LEVEL_KEY,
            TextReasoningOptionsResolver.BUDGET_KEY, TextReasoningOptionsResolver.INCLUDE_KEY,
            StructuredOutputSupport.ENABLED_KEY);

    public TokenDanceAnthropicMessagesTextProviderClient(TokenDanceTransport transport)
    {
        super(transport, TokenDanceProtocols.ANTHROPIC_MESSAGES, TokenDanceEndpoints.MESSAGES);
    }

    @Override
    protected Map<String, String> protocolHeaders()
    {
        return Map.of("anthropic-version", "2023-06-01");
    }

    @Override
    protected String buildBody(AiModelConfigVo modelConfig, MediaTextGenerateRequest request, boolean stream)
    {
        TokenDancePayloadSupport.rejectUnknownRequestOptions(modelConfig, request.getOptions(), REQUEST_OPTION_KEYS);
        TokenDancePayloadSupport.rejectUnpricedHostedTools(
                request.getOptions() == null ? null : request.getOptions().get("tools"));
        rejectUnmappedStructuredOutput(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", TokenDancePayloadSupport.upstreamModel(modelConfig, request.getModelName()));
        List<Map<String, Object>> messages = new ArrayList<>();
        List<String> system = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(request.getMessages()))
        {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages())
            {
                if (item == null)
                {
                    continue;
                }
                String role = StrUtil.blankToDefault(item.getRole(), "user").trim().toLowerCase();
                if ("system".equals(role))
                {
                    if (CollectionUtil.isNotEmpty(item.getParts()))
                    {
                        throw new ServiceException("系统消息仅支持文本");
                    }
                    if (StrUtil.isNotBlank(item.getContent()))
                    {
                        system.add(item.getContent());
                    }
                    continue;
                }
                if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role))
                {
                    throw new ServiceException("消息角色不支持");
                }
                String wireRole = "assistant".equals(role) ? "assistant" : "user";
                List<Map<String, Object>> parts = anthropicParts(item);
                // 并行函数结果必须在紧邻助手调用的同一 user 消息中回传。
                if ("tool".equals(role) && !messages.isEmpty() && "user".equals(messages.get(messages.size() - 1).get("role"))) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> prior = (List<Map<String, Object>>) messages.get(messages.size() - 1).get("content");
                    prior.addAll(parts);
                } else messages.add(Map.of("role", wireRole, "content", parts));
            }
        }
        if (StrUtil.isNotBlank(request.getPrompt()))
        {
            messages.add(Map.of("role", "user", "content",
                    List.of(Map.of("type", "text", "text", request.getPrompt()))));
        }
        if (messages.isEmpty())
        {
            throw new ServiceException("请输入文本内容");
        }
        body.put("messages", messages);
        if (!system.isEmpty())
        {
            body.put("system", String.join("\n\n", system));
        }
        body.put("stream", stream);
        Map<String, Object> options = TokenDancePayloadSupport.requestScopedOptions(
                modelConfig, request.getOptions(), SAFE_CONFIG_DEFAULTS);
        options = TextReasoningOptionsResolver.resolveAnthropic(modelConfig, request, options);
        TokenDancePayloadSupport.copyAllowlisted(body, options, OPTION_KEYS);
        Object outputTokens = options.get(TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY);
        body.put("max_tokens", outputTokens == null ? TextOutputLimitResolver.FALLBACK_OUTPUT_TOKENS : outputTokens);
        try
        {
            return MAPPER.writeValueAsString(body);
        }
        catch (JsonProcessingException exception)
        {
            throw new ServiceException("文本参数错误");
        }
    }

    private void rejectUnmappedStructuredOutput(MediaTextGenerateRequest request)
    {
        Boolean enabled = TokenDancePayloadSupport.bool(request.getOptions(),
                StructuredOutputSupport.ENABLED_KEY);
        if (Boolean.TRUE.equals(enabled))
        {
            throw new ServiceException("结构化输出未适配");
        }
    }

    @Override
    protected void acceptStreamEvent(JsonNode event, TextStreamCallbacks callbacks)
    {
        JsonNode delta = event.path("delta");
        String type = delta.path("type").asText("");
        if ("text_delta".equals(type))
        {
            callbacks.onDelta(delta.path("text").asText(""));
        }
        else if ("thinking_delta".equals(type))
        {
            callbacks.onReasoningDelta(delta.path("thinking").asText(""));
        }
        Map<String, Object> usage = TokenDanceResponseMapper.usage(event);
        if (usage != null)
        {
            callbacks.onUsage(usage);
        }
    }

    @Override
    protected ProviderSubmitResult parseSync(TokenDanceHttpResponse response, String auditBody)
    {
        JsonNode root = TokenDanceResponseMapper.readTree(response.bodyUtf8());
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        if (root != null)
        {
            for (JsonNode content : root.path("content"))
            {
                if ("text".equals(content.path("type").asText()))
                {
                    text.append(content.path("text").asText(""));
                }
                else if ("thinking".equals(content.path("type").asText()))
                {
                    reasoning.append(content.path("thinking").asText(""));
                }
            }
        }
        return ProviderSubmitResult.builder()
                .directText(text.toString())
                .directReasoning(reasoning.toString())
                .toolMessage(root == null ? null : TokenDanceToolMessages.sync(root, protocol()))
                .usage(TokenDanceResponseMapper.usage(root))
                .rawResponse(auditBody)
                .build();
    }

    private List<Map<String, Object>> anthropicParts(MediaTextGenerateRequest.TextMessageItem item)
    {
        List<Map<String, Object>> result = new ArrayList<>(TokenDanceToolMessages.anthropicBlocks(item));
        if ("tool".equals(item.getRole())) return result;
        if (StrUtil.isNotBlank(item.getContent()))
        {
            result.add(Map.of("type", "text", "text", item.getContent()));
        }
        if (CollectionUtil.isNotEmpty(item.getParts()))
        {
            for (MediaTextGenerateRequest.TextContentPart part : item.getParts())
            {
                if (part == null)
                {
                    continue;
                }
                String type = StrUtil.blankToDefault(part.getType(), "text").trim().toLowerCase();
                if ("text".equals(type) && StrUtil.isNotBlank(part.getText()))
                {
                    result.add(Map.of("type", "text", "text", part.getText()));
                }
                else if ("image".equals(type) && StrUtil.isNotBlank(part.getUrl()))
                {
                    result.add(Map.of("type", "image", "source",
                            Map.of("type", "url", "url", part.getUrl())));
                }
                else if ("video".equals(type) && StrUtil.isNotBlank(part.getUrl()))
                {
                    result.add(Map.of("type", "video", "source",
                            Map.of("type", "url", "url", part.getUrl())));
                }
                else
                {
                    throw new ServiceException("输入类型暂不支持");
                }
            }
        }
        if (result.isEmpty())
        {
            throw new ServiceException("消息内容不能为空");
        }
        return result;
    }
}
