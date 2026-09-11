package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** TokenDance Wan3.0 视频合成请求方言。 */
final class TokenDanceWan3VideoStrategy implements TokenDanceVideoProtocolStrategy
{
    private static final Set<String> PARAMETER_FIELDS = Set.of(
            "prompt_extend", "watermark", "seed");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.WAN3_VIDEO_SYNTHESIS;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.WAN3_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.WAN3_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.DASHSCOPE;
    }

    @Override
    public void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        int imageCount = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1)
                + (inputs.lastFrame == null ? 0 : 1);
        String cleaned = ReferencePromptSanitizer.sanitizeForWan3(request.getPrompt(), imageCount,
                inputs.referenceVideos.size(), inputs.referenceAudios.size());
        if (!java.util.Objects.equals(cleaned, request.getPrompt()))
        {
            request.setPrompt(cleaned);
        }
    }

    @Override
    public void normalizeRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        Map<String, Object> options = new LinkedHashMap<>(request.getOptions() == null ? Map.of() : request.getOptions());
        if (TokenDancePayloadSupport.text(options, "resolution", "size") == null) {
            options.put("resolution", StrUtil.blankToDefault(ModelCapabilityResolver.resolveSize(modelConfig, null), "1080P"));
        }
        if (request.getDurationSeconds() == null) {
            Integer configured = modelConfig.getDefaultDurationSeconds();
            request.setDurationSeconds(configured == null ? 5 : configured);
        }
        BigDecimal inputSeconds = BigDecimal.ZERO;
        if (request.getResolvedReferenceVideos() != null) {
            for (var video : request.getResolvedReferenceVideos()) {
                if (video != null && video.isTrusted() && video.getDurationMs() != null) {
                    inputSeconds = inputSeconds.add(BigDecimal.valueOf(video.getDurationMs()).movePointLeft(3));
                }
            }
        }
        // 规划报价使用副本校验协议，计费上界必须先冻结在原请求，不能在副本里计算后丢失。
        options.put("billingDurationSeconds", request.getDurationSeconds() == -1
                ? BigDecimal.valueOf(30).subtract(inputSeconds).setScale(0, RoundingMode.CEILING).intValueExact()
                : request.getDurationSeconds());
        request.setOptions(options);
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        normalizeRequest(modelConfig, request);
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request,
                // 预扣时长仅留在站内，不进入上游 parameters。
                Set.of("file", "fileUrl", "link", "linkUrl", "prompt_extend", "billingDurationSeconds"));
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        String file = referenceOption(inputs.options, "file", "fileUrl");
        String link = referenceOption(inputs.options, "link", "linkUrl");
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(file) && StrUtil.isNotBlank(link), "文件链接不能同时用");
        boolean hasExternalReference = StrUtil.isNotBlank(file) || StrUtil.isNotBlank(link);
        if (hasExternalReference) {
            validateReferenceUrl(StrUtil.isNotBlank(file) ? file : link);
            TokenDanceVideoBodySupport.require(Boolean.TRUE.equals(
                    TokenDancePayloadSupport.bool(inputs.options, "prompt_extend")), "请开启提示词改写");
        }

        boolean hasReferences = TokenDanceVideoBodySupport.hasAny(
                inputs.referenceImages, inputs.referenceVideos, inputs.referenceAudios) || hasExternalReference;
        boolean hasFrames = StrUtil.isNotBlank(inputs.firstFrame) || StrUtil.isNotBlank(inputs.lastFrame);
        TokenDanceVideoBodySupport.reject(hasFrames && hasReferences, "输入组合不支持");
        TokenDanceVideoBodySupport.require(StrUtil.isBlank(inputs.lastFrame)
                || StrUtil.isNotBlank(inputs.firstFrame), "缺少首帧");
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.prompt)
                || hasFrames || hasReferences, "缺少生成内容");
        validateParameters(request, inputs);

        List<Map<String, Object>> media = new ArrayList<>();
        if (hasFrames)
        {
            if (StrUtil.isNotBlank(inputs.firstFrame))
            {
                media.add(TokenDanceVideoBodySupport.typedUrl("first_frame", inputs.firstFrame));
            }
            if (StrUtil.isNotBlank(inputs.lastFrame))
            {
                media.add(TokenDanceVideoBodySupport.typedUrl("last_frame", inputs.lastFrame));
            }
        }
        else
        {
            inputs.referenceImages.forEach(url -> media.add(
                    TokenDanceVideoBodySupport.typedUrl("reference_image", url)));
            inputs.referenceVideos.forEach(url -> media.add(
                    TokenDanceVideoBodySupport.typedUrl("reference_video", url)));
            inputs.referenceAudios.forEach(url -> media.add(
                    TokenDanceVideoBodySupport.typedUrl("reference_audio", url)));
            if (StrUtil.isNotBlank(file)) media.add(TokenDanceVideoBodySupport.typedUrl("file", file));
            if (StrUtil.isNotBlank(link)) media.add(TokenDanceVideoBodySupport.typedUrl("link", link));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        TokenDanceVideoBodySupport.putIfPresent(input, "prompt", inputs.prompt);
        if (!media.isEmpty())
        {
            input.put("media", media);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        TokenDanceVideoBodySupport.putIfPresent(parameters, "resolution",
                TokenDanceVideoBodySupport.upperResolution(inputs.resolution));
        TokenDanceVideoBodySupport.putIfPresent(parameters, "ratio", inputs.ratio);
        TokenDanceVideoBodySupport.putIfPresent(parameters, "duration", inputs.duration);
        TokenDanceVideoBodySupport.putIfPresent(parameters, "audio", inputs.generateAudio);
        TokenDancePayloadSupport.copyAllowlisted(parameters, inputs.options, PARAMETER_FIELDS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inputs.model);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    private void validateParameters(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs) {
        TokenDanceVideoBodySupport.require(inputs.referenceImages.size() <= 10, "参考图片数量超限");
        TokenDanceVideoBodySupport.require(inputs.referenceVideos.size() <= 5, "参考视频数量超限");
        TokenDanceVideoBodySupport.require(inputs.referenceAudios.size() <= 5, "参考音频数量超限");
        TokenDanceVideoBodySupport.require(inputs.resolution == null || Set.of("480P", "720P", "1080P")
                .contains(TokenDanceVideoBodySupport.upperResolution(inputs.resolution)), "分辨率不支持");
        TokenDanceVideoBodySupport.require(inputs.ratio == null || Set.of("adaptive", "16:9", "4:3", "1:1", "3:4", "9:16")
                .contains(inputs.ratio.toLowerCase(java.util.Locale.ROOT)), "画面比例不支持");
        int duration = inputs.duration == null ? 5 : inputs.duration;
        TokenDanceVideoBodySupport.require(duration == -1 || duration >= 2 && duration <= 30, "视频时长不支持");
        BigDecimal inputSeconds = BigDecimal.ZERO;
        if (request.getResolvedReferenceVideos() != null) {
            for (var video : request.getResolvedReferenceVideos()) {
                if (video == null || !video.isTrusted() || video.getDurationMs() == null) continue;
                BigDecimal seconds = BigDecimal.valueOf(video.getDurationMs()).movePointLeft(3);
                TokenDanceVideoBodySupport.require(seconds.compareTo(BigDecimal.ONE) >= 0
                        && seconds.compareTo(BigDecimal.valueOf(15)) <= 0, "参考视频时长超限");
                inputSeconds = inputSeconds.add(seconds);
            }
        }
        TokenDanceVideoBodySupport.require(inputSeconds.compareTo(BigDecimal.valueOf(15)) <= 0, "参考视频总时长超限");
        TokenDanceVideoBodySupport.require(duration < 0 || inputSeconds.add(BigDecimal.valueOf(duration))
                .compareTo(BigDecimal.valueOf(30)) <= 0, "输入输出总时长超限");
        TokenDancePayloadSupport.bool(inputs.options, "prompt_extend");
        TokenDancePayloadSupport.bool(inputs.options, "watermark");
        Object seed = inputs.options.get("seed");
        if (seed != null) {
            TokenDanceVideoBodySupport.require(seed instanceof Number, "种子参数无效");
            BigDecimal number = new BigDecimal(seed.toString());
            TokenDanceVideoBodySupport.require(number.stripTrailingZeros().scale() <= 0
                    && number.compareTo(BigDecimal.valueOf(-1)) >= 0
                    && number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0, "种子参数无效");
        }
    }

    private void validateReferenceUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            TokenDanceVideoBodySupport.require(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null,
                    "参考地址无效");
        } catch (IllegalArgumentException exception) {
            TokenDanceVideoBodySupport.require(false, "参考地址无效");
        }
    }

    private String referenceOption(Map<String, Object> options, String name, String alias) {
        String value = TokenDancePayloadSupport.text(options, name);
        String alternate = TokenDancePayloadSupport.text(options, alias);
        TokenDanceVideoBodySupport.require(value == null || alternate == null || value.equals(alternate), "参考地址参数冲突");
        return value == null ? alternate : value;
    }
}
