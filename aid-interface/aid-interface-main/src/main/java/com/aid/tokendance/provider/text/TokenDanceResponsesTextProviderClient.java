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

/** TokenDance OpenAI Responses 原生协议。 */
@Component
public class TokenDanceResponsesTextProviderClient extends AbstractTokenDanceTextProviderClient
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SAFE_CONFIG_DEFAULTS = Set.of(
            "temperature", "top_p", "metadata", "store", "truncation");
    private static final Set<String> OPTION_KEYS = Set.of(
            "temperature", "top_p", "tools", "tool_choice", "parallel_tool_calls",
            "previous_response_id", "metadata", "reasoning", "store", "truncation");
    private static final Set<String> REQUEST_OPTION_KEYS = Set.of(
            "temperature", "top_p", "tools", "tool_choice", "parallel_tool_calls",
            "previous_response_id", "metadata", "reasoning", "store", "truncation",
            TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY,
            com.aid.media.provider.TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
            TextReasoningOptionsResolver.ENABLED_KEY, TextReasoningOptionsResolver.LEVEL_KEY,
            TextReasoningOptionsResolver.BUDGET_KEY, TextReasoningOptionsResolver.INCLUDE_KEY,
            StructuredOutputSupport.ENABLED_KEY);

    public TokenDanceResponsesTextProviderClient(TokenDanceTransport transport)
    {
        super(transport, TokenDanceProtocols.OPENAI_RESPONSES, TokenDanceEndpoints.RESPONSES);
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
        body.put("input", buildInput(request));
        body.put("stream", stream);
        if (TokenDanceToolMessages.requested(request)) body.put("include", List.of("reasoning.encrypted_content"));
        Map<String, Object> options = TokenDancePayloadSupport.requestScopedOptions(
                modelConfig, request.getOptions(), SAFE_CONFIG_DEFAULTS);
        options = TextReasoningOptionsResolver.resolveResponses(modelConfig, request, options);
        TokenDancePayloadSupport.copyAllowlisted(body, options, OPTION_KEYS);
        Object outputTokens = options.get(TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY);
        if (outputTokens != null)
        {
            body.put("max_output_tokens", outputTokens);
        }
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
        String type = event.path("type").asText("");
        if ("response.output_text.delta".equals(type))
        {
            callbacks.onDelta(event.path("delta").asText(""));
        }
        else if ("response.reasoning_summary_text.delta".equals(type))
        {
            callbacks.onReasoningDelta(event.path("delta").asText(""));
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
            String direct = root.path("output_text").asText(null);
            if (StrUtil.isNotBlank(direct))
            {
                text.append(direct);
            }
            for (JsonNode item : root.path("output"))
            {
                if (StrUtil.isBlank(direct))
                {
                    for (JsonNode content : item.path("content"))
                    {
                        if ("output_text".equals(content.path("type").asText()))
                        {
                            text.append(content.path("text").asText(""));
                        }
                    }
                }
                if ("reasoning".equals(item.path("type").asText()))
                {
                    for (JsonNode summary : item.path("summary"))
                    {
                        reasoning.append(summary.path("text").asText(""));
                    }
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

    private List<Map<String, Object>> buildInput(MediaTextGenerateRequest request)
    {
        List<Map<String, Object>> input = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(request.getMessages()))
        {
            for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages())
            {
                if (message == null)
                {
                    continue;
                }
                if (message.getResponseItems() != null) {
                    input.addAll(TokenDanceToolMessages.responseCalls(message));
                    continue;
                }
                if ("tool".equals(message.getRole())) {
                    input.addAll(TokenDanceToolMessages.responseCalls(message));
                    continue;
                }
                if (TokenDanceToolMessages.hasCalls(message) && StrUtil.isBlank(message.getContent())
                        && CollectionUtil.isEmpty(message.getParts())) {
                    input.addAll(TokenDanceToolMessages.responseCalls(message));
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", normalizeRole(message.getRole()));
                item.put("content", responseParts(message));
                input.add(item);
                input.addAll(TokenDanceToolMessages.responseCalls(message));
            }
        }
        if (StrUtil.isNotBlank(request.getPrompt()))
        {
            input.add(Map.of("role", "user", "content",
                    List.of(Map.of("type", "input_text", "text", request.getPrompt()))));
        }
        if (input.isEmpty())
        {
            throw new ServiceException("请输入文本内容");
        }
        return input;
    }

    private List<Map<String, Object>> responseParts(MediaTextGenerateRequest.TextMessageItem message)
    {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(message.getContent()))
        {
            parts.add(Map.of("type", "assistant".equals(message.getRole()) ? "output_text" : "input_text", "text", message.getContent()));
        }
        if (CollectionUtil.isNotEmpty(message.getParts()))
        {
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts())
            {
                if (part == null)
                {
                    continue;
                }
                String type = StrUtil.blankToDefault(part.getType(), "text").trim().toLowerCase();
                if ("text".equals(type) && StrUtil.isNotBlank(part.getText()))
                {
                    parts.add(Map.of("type", "assistant".equals(normalizeRole(message.getRole())) ? "output_text" : "input_text", "text", part.getText()));
                }
                else if ("image".equals(type) && StrUtil.isNotBlank(part.getUrl()))
                {
                    Map<String, Object> image = new LinkedHashMap<>();
                    image.put("type", "input_image");
                    image.put("image_url", part.getUrl());
                    if (StrUtil.isNotBlank(part.getDetail()))
                    {
                        image.put("detail", part.getDetail());
                    }
                    parts.add(image);
                }
                else
                {
                    throw new ServiceException("输入类型暂不支持");
                }
            }
        }
        if (parts.isEmpty())
        {
            throw new ServiceException("消息内容不能为空");
        }
        return parts;
    }

    private String normalizeRole(String role)
    {
        String normalized = StrUtil.blankToDefault(role, "user").trim().toLowerCase();
        if (!Set.of("user", "assistant", "system", "developer").contains(normalized))
        {
            throw new ServiceException("消息角色不支持");
        }
        return normalized;
    }
}
