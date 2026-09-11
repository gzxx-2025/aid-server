package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TokenDance MiniMax H3 视频生成请求方言。 */
final class TokenDanceMinimaxVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    @Override
    public String protocol()
    {
        return TokenDanceProtocols.MINIMAX_VIDEO_GENERATION_V2;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.MINIMAX_VIDEO_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.MINIMAX_VIDEO_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.MINIMAX;
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request,
                Set.of("videoScenario", "operation"));
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        boolean fastVariant = "minimax-h3-max".equalsIgnoreCase(inputs.model);
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.prompt), "缺少视频描述");
        TokenDanceVideoBodySupport.require(inputs.prompt.length() <= 7000, "视频描述过长");
        TokenDanceVideoBodySupport.reject(Boolean.TRUE.equals(inputs.generateAudio), "该协议不支持音频");
        if (StrUtil.isNotBlank(inputs.resolution))
        {
            TokenDanceVideoBodySupport.require((fastVariant ? Set.of("480p", "768p") : Set.of("768p", "2k")).contains(
                    TokenDanceVideoBodySupport.lowerResolution(inputs.resolution)), "分辨率不支持");
        }
        if (inputs.duration != null)
        {
            TokenDanceVideoBodySupport.require(inputs.duration >= (fastVariant ? 5 : 4) && inputs.duration <= 15,
                    "视频时长不支持");
        }
        if (StrUtil.isNotBlank(inputs.ratio))
        {
            TokenDanceVideoBodySupport.require(Set.of("adaptive", "21:9", "16:9", "4:3", "1:1", "3:4", "9:16")
                    .contains(inputs.ratio.trim().toLowerCase(Locale.ROOT)), "画面比例不支持");
        }

        String scene = StrUtil.blankToDefault(TokenDancePayloadSupport.text(inputs.options,
                "videoScenario", "operation"), "").toLowerCase(Locale.ROOT);
        String generateMode = StrUtil.blankToDefault(modelConfig.getGenerateMode(), "")
                .toLowerCase(Locale.ROOT);
        boolean listedReferenceMedia = TokenDanceVideoBodySupport.hasAny(
                inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios);
        boolean referenceMode = listedReferenceMedia || scene.contains("reference")
                || generateMode.contains("reference");
        TokenDanceVideoBodySupport.reject(fastVariant && referenceMode, "该模型不支持参考");
        TokenDanceVideoBodySupport.reject(referenceMode && StrUtil.isNotBlank(inputs.lastFrame),
                "输入组合不支持");

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(TokenDanceVideoBodySupport.content("text", inputs.prompt, null, null, null));
        if (referenceMode)
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "reference_image"));
            }
            inputs.referenceImages.forEach(url -> content.add(
                    TokenDanceVideoBodySupport.nestedMedia("image_url", url, "reference_image")));
            inputs.referenceVideos.forEach(url -> content.add(
                    TokenDanceVideoBodySupport.nestedMedia("video_url", url, "reference_video")));
            inputs.referenceAudios.forEach(url -> content.add(
                    TokenDanceVideoBodySupport.nestedMedia("audio_url", url, "reference_audio")));
            TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame)
                    || !inputs.referenceImages.isEmpty() || !inputs.referenceVideos.isEmpty(), "需要参考图片或视频");
        }
        else
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.firstFrame, "first_frame"));
            }
            if (StrUtil.isNotBlank(inputs.lastFrame))
            {
                TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame), "缺少首帧");
                content.add(TokenDanceVideoBodySupport.nestedMedia(
                        "image_url", inputs.lastFrame, "last_frame"));
            }
        }

        boolean textOnly = content.size() == 1;
        if (textOnly)
        {
            TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.ratio)
                    && !"adaptive".equalsIgnoreCase(inputs.ratio), "文生视频需要比例");
        }
        // 首帧/首尾帧的输出画幅由图片决定，业务画幅偏好（含项目默认值）不映射为 ratio。
        // 文生和多模态参考仍保留画幅校验与传参，不改变其他供应商或素材角色。

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inputs.model);
        TokenDanceVideoBodySupport.putIfPresent(body, "resolution",
                TokenDanceVideoBodySupport.upperResolution(inputs.resolution));
        TokenDanceVideoBodySupport.putIfPresent(body, "duration", inputs.duration);
        if (textOnly || referenceMode)
        {
            TokenDanceVideoBodySupport.putIfPresent(body, "ratio", inputs.ratio);
        }
        body.put("content", content);
        Object watermark = inputs.options.get("aigc_watermark");
        if (watermark != null)
        {
            body.put("aigc_watermark", watermark);
        }
        return body;
    }
}
