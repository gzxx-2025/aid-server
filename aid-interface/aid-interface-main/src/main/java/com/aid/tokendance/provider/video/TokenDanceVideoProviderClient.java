package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceHttpResponse;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TokenDance 视频供应商唯一强匹配门面。 */
@Component
public class TokenDanceVideoProviderClient implements VideoProviderClient
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TokenDanceTransport transport;
    private final Map<String, TokenDanceVideoProtocolStrategy> strategies;

    public TokenDanceVideoProviderClient(TokenDanceTransport transport)
    {
        this.transport = transport;
        Map<String, TokenDanceVideoProtocolStrategy> values = new LinkedHashMap<>();
        register(values, new TokenDanceSeedanceVideoStrategy());
        register(values, new TokenDanceKlingTextVideoStrategy());
        register(values, new TokenDanceKlingImageVideoStrategy());
        register(values, new TokenDanceKlingOmniVideoStrategy());
        register(values, new TokenDanceWan3VideoStrategy());
        register(values, new TokenDanceHappyHorseVideoStrategy());
        register(values, new TokenDanceMinimaxVideoStrategy());
        this.strategies = Map.copyOf(values);
    }

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.SEEDANCE_GENERATIONS;
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
    public void normalizeRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        requireStrategy(modelConfig.getProtocol()).normalizeRequest(modelConfig, request);
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDancePayloadSupport.requireModel(modelConfig, TokenDanceProtocols.VIDEO_PROTOCOLS);
        if (request == null)
        {
            throw new ServiceException("视频请求不能为空");
        }
        com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, requireStrategy(modelConfig.getProtocol()).buildBody(modelConfig, request), request);
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
            boolean planned)
    {
        validateRequest(modelConfig, planned ? plannedValidationCopy(request) : request);
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        validateRequest(modelConfig, request);
        if (request == null)
        {
            throw new ServiceException("视频请求不能为空");
        }
        TokenDanceVideoProtocolStrategy strategy = requireStrategy(modelConfig.getProtocol());
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        strategy.sanitizePrompt(request, inputs);
        try
        {
            TokenDanceHttpResponse response = transport.exchange("POST", modelConfig,
                    com.aid.tokendance.provider.common.TokenDanceEndpoints.submitPath(modelConfig, strategy.submitPath()), MAPPER.writeValueAsBytes(com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, strategy.buildBody(modelConfig, request), request)),
                    strategy.protocolHeaders());
            return TokenDanceVideoResponseMapper.submit(response, strategy.responseShape());
        }
        catch (IOException exception)
        {
            return ProviderSubmitResult.builder().rawResponse("上游请求失败").build();
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId)
    {
        TokenDancePayloadSupport.requireModel(modelConfig, TokenDanceProtocols.VIDEO_PROTOCOLS);
        TokenDanceVideoProtocolStrategy strategy = requireStrategy(modelConfig.getProtocol());
        try
        {
            String queryPath = TokenDanceEndpoints.queryPath(modelConfig, strategy.queryTemplate(), providerTaskId);
            TokenDanceHttpResponse response = transport.exchange("GET", modelConfig,
                    queryPath, null, strategy.protocolHeaders());
            return TokenDanceVideoResponseMapper.query(response, strategy.responseShape());
        }
        catch (Exception exception)
        {
            return ProviderTaskResult.builder()
                    .status("PROCESSING")
                    .errorMessage("上游查询暂不可用")
                    .rawErrorMessage(exception.getClass().getSimpleName())
                    .querySuccessful(Boolean.FALSE)
                    .terminalConfirmed(Boolean.FALSE)
                    .build();
        }
    }

    private TokenDanceVideoProtocolStrategy requireStrategy(String protocol)
    {
        TokenDanceVideoProtocolStrategy strategy = find(protocol);
        if (strategy == null)
        {
            TokenDancePayloadSupport.rejectUnknownProtocol(protocol);
        }
        return strategy;
    }

    private MediaVideoGenerateRequest plannedValidationCopy(MediaVideoGenerateRequest source)
    {
        if (source == null)
        {
            return null;
        }
        MediaVideoGenerateRequest target = new MediaVideoGenerateRequest();
        target.setModelName(source.getModelName());
        target.setProjectId(source.getProjectId());
        target.setEpisodeId(source.getEpisodeId());
        target.setPrompt(StrUtil.isBlank(source.getPrompt()) ? "[PLANNED_PROMPT]" : source.getPrompt());
        target.setTaskPromptDigest(source.getTaskPromptDigest());
        target.setImageUrl(source.getImageUrl());
        target.setDurationSeconds(source.getDurationSeconds());
        target.setAspectRatio(source.getAspectRatio());
        target.setOptions(source.getOptions() == null ? null : new LinkedHashMap<>(source.getOptions()));
        target.setAudio(source.getAudio());
        target.setBgm(source.getBgm());
        target.setAudioType(source.getAudioType());
        target.setVoiceId(source.getVoiceId());
        target.setReferenceAudios(source.getReferenceAudios() == null
                ? null : List.copyOf(source.getReferenceAudios()));
        target.setReferenceVideoRecordIds(source.getReferenceVideoRecordIds() == null
                ? null : List.copyOf(source.getReferenceVideoRecordIds()));
        target.setResolvedReferenceVideos(source.getResolvedReferenceVideos() == null
                ? null : List.copyOf(source.getResolvedReferenceVideos()));
        target.setRecordId(source.getRecordId());
        target.setCategory(source.getCategory());
        target.setBizTaskId(source.getBizTaskId());
        target.setBizTaskType(source.getBizTaskType());
        target.setParentTaskId(source.getParentTaskId());
        target.setUserId(source.getUserId());
        return target;
    }

    private TokenDanceVideoProtocolStrategy find(String protocol)
    {
        return protocol == null ? null : strategies.get(protocol.trim().toLowerCase());
    }

    private void register(Map<String, TokenDanceVideoProtocolStrategy> target,
            TokenDanceVideoProtocolStrategy strategy)
    {
        target.put(strategy.protocol().toLowerCase(), strategy);
    }
}
