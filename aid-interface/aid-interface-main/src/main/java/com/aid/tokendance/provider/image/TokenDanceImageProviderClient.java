package com.aid.tokendance.provider.image;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** TokenDance 图片供应商唯一强匹配门面。 */
@Component
public class TokenDanceImageProviderClient implements ImageProviderClient
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BASE64_RESPONSE_BYTES = 96 * 1024 * 1024;

    private final TokenDanceTransport transport;
    private final Map<String, TokenDanceImageProtocolStrategy> strategies;

    public TokenDanceImageProviderClient(TokenDanceTransport transport)
    {
        this.transport = transport;
        Map<String, TokenDanceImageProtocolStrategy> values = new LinkedHashMap<>();
        register(values, new TokenDanceArkImageStrategy());
        register(values, new TokenDanceOpenAiImageStrategy());
        this.strategies = Map.copyOf(values);
    }

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.ARK_IMAGE_GENERATIONS;
    }

    @Override
    public boolean supportsProtocol(String protocol)
    {
        return find(protocol) != null;
    }

    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return TokenDanceProtocols.isTokenDance(providerCode);
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaImageGenerateRequest request)
    {
        TokenDancePayloadSupport.requireModel(modelConfig, TokenDanceProtocols.IMAGE_PROTOCOLS);
        if (request == null || StrUtil.isBlank(request.getPrompt()))
        {
            throw new ServiceException("请输入图片描述");
        }
        Map<String, Object> body = com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, requireStrategy(modelConfig.getProtocol()).buildBody(modelConfig, request), request);
        if (body.get("size") instanceof String size) {
            request.setSize(size);
        }
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaImageGenerateRequest request,
            boolean planned)
    {
        MediaImageGenerateRequest validated = planned ? plannedValidationCopy(request) : request;
        validateRequest(modelConfig, validated);
        request.setSize(validated.getSize());
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request)
    {
        validateRequest(modelConfig, request);
        TokenDanceImageProtocolStrategy strategy = requireStrategy(modelConfig.getProtocol());
        if (request == null || StrUtil.isBlank(request.getPrompt()))
        {
            throw new ServiceException("请输入图片描述");
        }
        int referenceCount = TokenDancePayloadSupport.concatUrls(request.getReferenceImageUrl(),
                request.getOptions() == null ? null : request.getOptions().get("referenceImages"),
                request.getOptions() == null ? null : request.getOptions().get("images")).size();
        ReferencePromptSanitizer.sanitizeInPlace(request, referenceCount);
        byte[] requestBody = null;
        try
        {
            Map<String, Object> body = com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, strategy.buildBody(modelConfig, request), request);
            requestBody = MAPPER.writeValueAsBytes(body);
            boolean stream = Boolean.TRUE.equals(body.get("stream"));
            String outputFormat = body.get("output_format") == null
                    ? null : String.valueOf(body.get("output_format"));
            AtomicReference<TokenDanceImageResponseSupport.DecodedImages> streamed =
                    new AtomicReference<>();
            TokenDanceHttpResponse response = stream
                    ? transport.exchangeStream("POST", modelConfig, com.aid.tokendance.provider.common.TokenDanceEndpoints.submitPath(modelConfig, strategy.endpoint()),
                            requestBody, Map.of(), (input, status, contentType, recovery) ->
                            {
                                if (contentType == null || !contentType.toLowerCase(Locale.ROOT)
                                        .contains("text/event-stream"))
                                {
                                    throw new ServiceException("图片流响应无效");
                                }
                                streamed.set(TokenDanceImageResponseSupport.decodeSse(
                                        input, outputFormat));
                            })
                    : exchangeImage(modelConfig, strategy, body, requestBody);
            if (!response.isSuccessful())
            {
                return ProviderSubmitResult.builder()
                        .rawResponse(TokenDanceResponseMapper.auditBody(response)).build();
            }
            TokenDanceImageResponseSupport.DecodedImages decoded = streamed.get();
            if (!stream)
            {
                JsonNode root = MAPPER.readTree(response.openBodyStream());
                decoded = TokenDanceImageResponseSupport.decodeJson(root, outputFormat);
            }
            List<String> urls = decoded == null ? List.of() : decoded.urls();
            int expectedCount = request.getExpectedImageCount() == null
                    ? 1 : request.getExpectedImageCount();
            if (urls.size() > expectedCount)
            {
                throw new ServiceException("图片数量异常");
            }
            String audit = decoded == null ? null
                    : TokenDanceImageResponseSupport.audit(decoded, response.getRecoveryAction());
            if (urls.isEmpty())
            {
                return ProviderSubmitResult.builder().rawResponse(audit).build();
            }
            return ProviderSubmitResult.builder()
                    .directUrl(urls.get(0))
                    .resultUrls(List.copyOf(urls))
                    .resultCount(urls.size())
                    .usage(decoded.usage())
                    .rawResponse(audit)
                    .build();
        }
        catch (IOException exception)
        {
            return ProviderSubmitResult.builder().rawResponse("上游请求失败").build();
        }
        finally
        {
            if (requestBody != null)
            {
                Arrays.fill(requestBody, (byte) 0);
            }
        }
    }

    private TokenDanceHttpResponse exchangeImage(AiModelConfigVo modelConfig,
            TokenDanceImageProtocolStrategy strategy, Map<String, Object> body,
            byte[] requestBody) throws IOException
    {
        if ("b64_json".equals(body.get("response_format")))
        {
            return transport.exchangeBounded("POST", modelConfig, com.aid.tokendance.provider.common.TokenDanceEndpoints.submitPath(modelConfig, strategy.endpoint()),
                    requestBody, Map.of(), MAX_BASE64_RESPONSE_BYTES);
        }
        return transport.exchange("POST", modelConfig, com.aid.tokendance.provider.common.TokenDanceEndpoints.submitPath(modelConfig, strategy.endpoint()),
                requestBody, Map.of());
    }

    private MediaImageGenerateRequest plannedValidationCopy(MediaImageGenerateRequest source)
    {
        if (source == null)
        {
            return null;
        }
        MediaImageGenerateRequest target = new MediaImageGenerateRequest();
        target.setModelName(source.getModelName());
        target.setProjectId(source.getProjectId());
        target.setEpisodeId(source.getEpisodeId());
        target.setPrompt(StrUtil.isBlank(source.getPrompt()) ? "[PLANNED_PROMPT]" : source.getPrompt());
        target.setTaskPromptDigest(source.getTaskPromptDigest());
        target.setSize(source.getSize());
        target.setNegativePrompt(source.getNegativePrompt());
        target.setReferenceImageUrl(source.getReferenceImageUrl());
        target.setOptions(source.getOptions() == null ? null : new LinkedHashMap<>(source.getOptions()));
        target.setRecordId(source.getRecordId());
        target.setCategory(source.getCategory());
        target.setExpectedImageCount(source.getExpectedImageCount());
        target.setBizTaskId(source.getBizTaskId());
        target.setBizTaskType(source.getBizTaskType());
        target.setUserId(source.getUserId());
        return target;
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId)
    {
        return ProviderTaskResult.builder()
                .status("PROCESSING")
                .errorMessage("同步图片无需查询")
                .querySuccessful(Boolean.FALSE)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    private TokenDanceImageProtocolStrategy requireStrategy(String protocol)
    {
        TokenDanceImageProtocolStrategy strategy = find(protocol);
        if (strategy == null)
        {
            TokenDancePayloadSupport.rejectUnknownProtocol(protocol);
        }
        return strategy;
    }

    private TokenDanceImageProtocolStrategy find(String protocol)
    {
        if (protocol == null)
        {
            return null;
        }
        return strategies.get(protocol.trim().toLowerCase());
    }

    private void register(Map<String, TokenDanceImageProtocolStrategy> target,
            TokenDanceImageProtocolStrategy strategy)
    {
        target.put(strategy.protocol().toLowerCase(), strategy);
    }
}
