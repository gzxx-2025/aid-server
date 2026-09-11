package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TokenDance 可灵图生视频请求方言。 */
final class TokenDanceKlingImageVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    @Override
    public String protocol()
    {
        return TokenDanceProtocols.KLING_IMAGE_TO_VIDEO;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.KLING_IMAGE_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.KLING_IMAGE_QUERY;
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
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame), "请选择首帧");
        TokenDanceVideoBodySupport.require(StrUtil.isBlank(inputs.prompt) || inputs.prompt.length() <= 2500,
                "视频描述过长");
        TokenDanceVideoBodySupport.reject(TokenDanceVideoBodySupport.hasAny(
                inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios), "输入组合不支持");
        TokenDanceVideoBodySupport.reject(Boolean.TRUE.equals(inputs.generateAudio), "该协议不支持音频");
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.lastFrame)
                && inputs.model.toLowerCase(java.util.Locale.ROOT).contains("turbo"),
                "Turbo 模型不支持尾帧");

        List<Map<String, Object>> contents = new ArrayList<>();
        if (StrUtil.isNotBlank(inputs.prompt))
        {
            contents.add(TokenDanceVideoBodySupport.content("prompt", inputs.prompt, null, null, null));
        }
        contents.add(TokenDanceVideoBodySupport.content(
                "first_frame", null, inputs.firstFrame, null, null));
        if (StrUtil.isNotBlank(inputs.lastFrame))
        {
            contents.add(TokenDanceVideoBodySupport.content(
                    "last_frame", null, inputs.lastFrame, null, null));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_name", inputs.model);
        body.put("contents", contents);
        body.put("settings", TokenDanceKlingTextVideoStrategy.settings(inputs));
        Map<String, Object> options = TokenDanceVideoBodySupport.watermarkOptions(inputs);
        if (!options.isEmpty())
        {
            body.put("options", options);
        }
        return body;
    }
}
