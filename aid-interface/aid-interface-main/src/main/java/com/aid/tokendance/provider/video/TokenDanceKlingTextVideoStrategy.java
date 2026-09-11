package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TokenDance 可灵文生视频请求方言。 */
final class TokenDanceKlingTextVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    @Override
    public String protocol()
    {
        return TokenDanceProtocols.KLING_TEXT_TO_VIDEO;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.KLING_TEXT_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.KLING_TEXT_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.KLING;
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request, Set.of());
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.prompt), "缺少视频描述");
        TokenDanceVideoBodySupport.require(inputs.prompt.length() <= 2500, "视频描述过长");
        TokenDanceVideoBodySupport.reject(inputs.hasReferences(), "文生视频不接收素材");
        TokenDanceVideoBodySupport.reject(Boolean.TRUE.equals(inputs.generateAudio), "该协议不支持音频");

        Map<String, Object> settings = settings(inputs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_name", inputs.model);
        body.put("prompt", inputs.prompt);
        body.put("settings", settings);
        Map<String, Object> options = TokenDanceVideoBodySupport.watermarkOptions(inputs);
        if (!options.isEmpty())
        {
            body.put("options", options);
        }
        return body;
    }

    static Map<String, Object> settings(TokenDanceVideoInputs inputs)
    {
        Map<String, Object> settings = new LinkedHashMap<>();
        String resolution = TokenDanceVideoBodySupport.lowerResolution(inputs.resolution);
        if (resolution != null)
        {
            boolean turbo = inputs.model.toLowerCase(Locale.ROOT).contains("turbo");
            TokenDanceVideoBodySupport.require((turbo ? Set.of("720p", "1080p")
                    : Set.of("720p", "1080p", "4k")).contains(resolution), "分辨率不支持");
            settings.put("resolution", resolution);
        }
        if (inputs.duration != null)
        {
            TokenDanceVideoBodySupport.require(inputs.duration >= 3 && inputs.duration <= 15,
                    "视频时长不支持");
        }
        TokenDanceVideoBodySupport.putIfPresent(settings, "duration", inputs.duration);
        if (StrUtil.isNotBlank(inputs.ratio))
        {
            TokenDanceVideoBodySupport.require(Set.of("16:9", "9:16", "1:1").contains(inputs.ratio.trim()),
                    "画面比例不支持");
            settings.put("aspect_ratio", inputs.ratio.trim());
        }
        return settings;
    }
}
