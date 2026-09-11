package com.aid.tokendance.provider.text;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.StructuredOutputSupport;
import com.aid.media.provider.TextChatOpenAiPayloadBuilder;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.provider.TextStreamCallbacks;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TokenDance OpenAI Chat Completions 原生协议。 */
@Component
public class TokenDanceOpenAiChatTextProviderClient extends AbstractTokenDanceTextProviderClient
{
    private static final Set<String> SAFE_CONFIG_DEFAULTS = Set.of(
            "temperature", "top_p", "frequency_penalty", "presence_penalty",
            "seed", "stop", "response_format", "logprobs", "top_logprobs");
    private static final Set<String> REQUEST_OPTION_KEYS = Set.of(
            "temperature", "top_p", "frequency_penalty", "presence_penalty",
            "seed", "stop", "response_format", "logprobs", "top_logprobs",
            "tools", "tool_choice", "parallel_tool_calls", "user", "service_tier",
            "verbosity", TextReasoningOptionsResolver.ENABLED_KEY,
            TextReasoningOptionsResolver.LEVEL_KEY, TextReasoningOptionsResolver.BUDGET_KEY,
            TextReasoningOptionsResolver.INCLUDE_KEY, TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
            StructuredOutputSupport.ENABLED_KEY,
            TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY,
            TextOutputLimitResolver.OUTPUT_TOKEN_API_FIELD_KEY);

    public TokenDanceOpenAiChatTextProviderClient(TokenDanceTransport transport)
    {
        super(transport, TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS,
                TokenDanceEndpoints.CHAT_COMPLETIONS);
    }

    @Override
    protected String buildBody(AiModelConfigVo modelConfig, MediaTextGenerateRequest request, boolean stream)
    {
        TokenDancePayloadSupport.rejectUnknownRequestOptions(modelConfig, request.getOptions(), REQUEST_OPTION_KEYS);
        TokenDancePayloadSupport.rejectUnpricedHostedTools(
                request.getOptions() == null ? null : request.getOptions().get("tools"));
        String model = TokenDancePayloadSupport.upstreamModel(modelConfig, request.getModelName());
        List<Map<String, Object>> messages = TextChatOpenAiPayloadBuilder.buildMessageMaps(modelConfig, request, true);
        Map<String, Object> merged = TokenDancePayloadSupport.requestScopedOptions(
                modelConfig, request.getOptions(), SAFE_CONFIG_DEFAULTS);
        Map<String, Object> options = new LinkedHashMap<>();
        TokenDancePayloadSupport.copyAllowlisted(options, merged, REQUEST_OPTION_KEYS);
        options = TextReasoningOptionsResolver.resolveOpenAiCompatible(modelConfig, request, options);
        if (options == null) options = new LinkedHashMap<>();
        TokenDanceKimiOptions.apply(model, request, options);
        options = StructuredOutputSupport.applyJsonModeIfSupported(modelConfig, messages, options);
        return TextChatOpenAiPayloadBuilder.buildChatCompletionsJsonBody(model, messages, stream, options);
    }

    @Override
    protected void acceptStreamEvent(JsonNode event, TextStreamCallbacks callbacks)
    {
        JsonNode delta = event.path("choices").path(0).path("delta");
        String content = delta.path("content").asText(null);
        if (StrUtil.isNotEmpty(content))
        {
            callbacks.onDelta(content);
        }
        String reasoning = delta.path("reasoning_content").asText(null);
        if (StrUtil.isNotEmpty(reasoning))
        {
            callbacks.onReasoningDelta(reasoning);
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
        String content = root == null ? null
                : root.path("choices").path(0).path("message").path("content").asText(null);
        String reasoning = root == null ? null
                : root.path("choices").path(0).path("message").path("reasoning_content").asText(null);
        return ProviderSubmitResult.builder()
                .directText(content)
                .directReasoning(reasoning)
                .toolMessage(root == null ? null : TokenDanceToolMessages.sync(root, protocol()))
                .usage(TokenDanceResponseMapper.usage(root))
                .rawResponse(auditBody)
                .build();
    }
}
