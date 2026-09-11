package com.aid.tokendance.provider.image;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** TokenDance OpenAI Image Generations 请求方言；不冒充未声明的 edits。 */
final class TokenDanceOpenAiImageStrategy implements TokenDanceImageProtocolStrategy
{
    private static final Set<String> OPTIONAL_FIELDS = Set.of(
            "quality", "style", "background", "output_format", "moderation", "user");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "quality", "style", "background", "output_format", "moderation", "user",
            "referenceImages", "images", "n");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.OPENAI_IMAGE_GENERATIONS;
    }

    @Override
    public String endpoint()
    {
        return TokenDanceEndpoints.OPENAI_IMAGES;
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaImageGenerateRequest request)
    {
        Map<String, Object> requestOptions = request.getOptions() == null
                ? Map.of() : request.getOptions();
        String model = TokenDancePayloadSupport.upstreamModel(modelConfig, request.getModelName());
        boolean seedreamLite = "seedream-5.0-lite".equalsIgnoreCase(model);
        Set<String> allowed = new java.util.LinkedHashSet<>(REQUEST_FIELDS);
        if (seedreamLite) allowed.addAll(Set.of("aspect_ratio", "aspectRatio", "ratio"));
        TokenDancePayloadSupport.rejectUnknownRequestOptions(modelConfig, requestOptions, allowed);
        if (StrUtil.isNotBlank(request.getNegativePrompt()))
        {
            throw new ServiceException("该协议不支持负向词");
        }
        Map<String, Object> options = TokenDancePayloadSupport.requestScopedOptions(
                modelConfig, requestOptions, OPTIONAL_FIELDS);
        if (StrUtil.isNotBlank(request.getReferenceImageUrl())
                || !TokenDancePayloadSupport.urls(requestOptions.get("referenceImages")).isEmpty()
                || !TokenDancePayloadSupport.urls(requestOptions.get("images")).isEmpty())
        {
            throw new ServiceException("该协议不支持参考图");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", request.getPrompt());
        body.put("n", request.getExpectedImageCount() == null ? 1 : request.getExpectedImageCount());
        String size = seedreamLite
                ? TokenDanceArkImageStrategy.resolveSeedreamSize(model, request.getSize(), requestOptions)
                : request.getSize();
        if (StrUtil.isNotBlank(size))
        {
            body.put("size", size.trim().replace('×', 'x').replace('*', 'x'));
        }
        TokenDancePayloadSupport.copyAllowlisted(body, options, OPTIONAL_FIELDS);
        body.put("response_format", "url");
        return body;
    }
}
