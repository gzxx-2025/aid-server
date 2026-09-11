package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/** TokenDance HappyHorse 视频合成请求方言。 */
final class TokenDanceHappyHorseVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("图片(\\d+)");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.HAPPYHORSE_VIDEO_SYNTHESIS;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.HAPPYHORSE_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.HAPPYHORSE_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.DASHSCOPE;
    }

    @Override
    public Map<String, String> protocolHeaders()
    {
        return Map.of("X-DashScope-Async", "enable");
    }

    @Override
    public void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        int imageCount = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1);
        ReferencePromptSanitizer.sanitizeInPlace(request, imageCount, 0);
        if (request != null && mode(inputs.model) == Mode.REFERENCE)
        {
            Matcher matcher = IMAGE_REFERENCE.matcher(StrUtil.blankToDefault(request.getPrompt(), ""));
            request.setPrompt(matcher.replaceAll("[Image $1]"));
        }
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request, Set.of());
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.prompt), "缺少视频描述");
        TokenDanceVideoBodySupport.reject(!inputs.referenceAudios.isEmpty(), "该协议不支持参考音频");
        // HappyHorse 在当前 TokenDance 协议下固定音画同出，协议不接收 generate_audio。
        // 公共能力层会按 defaultAudio=true 归一化缺省值；仅拒绝调用方试图关闭固定音频。
        TokenDanceVideoBodySupport.reject(Boolean.FALSE.equals(inputs.generateAudio), "该模型不支持关闭音频");
        Mode mode = mode(inputs.model);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", inputs.prompt);
        switch (mode)
        {
            case TEXT -> TokenDanceVideoBodySupport.reject(inputs.hasReferences(), "文生视频不接收素材");
            case IMAGE -> imageInput(inputs, input);
            case REFERENCE -> referenceInput(inputs, input);
            case EDIT -> editInput(inputs, input);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        TokenDanceVideoBodySupport.putIfPresent(parameters, "size", size(inputs.resolution, inputs.ratio));
        TokenDanceVideoBodySupport.putIfPresent(parameters, "duration", inputs.duration);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inputs.model);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    private void imageInput(TokenDanceVideoInputs inputs, Map<String, Object> input)
    {
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.firstFrame), "请选择首帧");
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.lastFrame)
                || TokenDanceVideoBodySupport.hasAny(inputs.referenceImages, inputs.referenceVideos),
                "输入组合不支持");
        input.put("img_url", inputs.firstFrame);
    }

    private void referenceInput(TokenDanceVideoInputs inputs, Map<String, Object> input)
    {
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.lastFrame)
                || !inputs.referenceVideos.isEmpty(), "输入组合不支持");
        List<String> images = new ArrayList<>();
        if (StrUtil.isNotBlank(inputs.firstFrame))
        {
            images.add(inputs.firstFrame);
        }
        images.addAll(inputs.referenceImages);
        TokenDanceVideoBodySupport.require(!images.isEmpty(), "请提供参考图片");
        input.put("ref_images_url", List.copyOf(images));
    }

    private void editInput(TokenDanceVideoInputs inputs, Map<String, Object> input)
    {
        TokenDanceVideoBodySupport.reject(StrUtil.isNotBlank(inputs.firstFrame)
                || StrUtil.isNotBlank(inputs.lastFrame) || !inputs.referenceImages.isEmpty(), "输入组合不支持");
        TokenDanceVideoBodySupport.require(inputs.referenceVideos.size() == 1, "编辑视频数量错误");
        input.put("video_url", inputs.referenceVideos.get(0));
    }

    private Mode mode(String model)
    {
        String normalized = StrUtil.blankToDefault(model, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("video-edit")) return Mode.EDIT;
        if (normalized.contains("-i2v")) return Mode.IMAGE;
        if (normalized.contains("-r2v")) return Mode.REFERENCE;
        if (normalized.contains("-t2v")) return Mode.TEXT;
        throw new com.aid.common.exception.ServiceException("模型视频场景错误");
    }

    private String size(String resolution, String ratio)
    {
        String value = StrUtil.trimToNull(resolution);
        if (value == null)
        {
            return null;
        }
        String normalized = value.replace('×', '*').replace('x', '*').toUpperCase(Locale.ROOT);
        if (normalized.contains("*"))
        {
            return normalized;
        }
        int shortSide = switch (normalized)
        {
            case "480P" -> 480;
            case "720P" -> 720;
            case "1080P" -> 1080;
            default -> throw new com.aid.common.exception.ServiceException("分辨率不支持");
        };
        String normalizedRatio = StrUtil.blankToDefault(ratio, "16:9").trim();
        return switch (normalizedRatio)
        {
            case "16:9" -> dimensions(shortSide * 16 / 9, shortSide);
            case "9:16" -> dimensions(shortSide, shortSide * 16 / 9);
            case "4:3" -> dimensions(shortSide * 4 / 3, shortSide);
            case "3:4" -> dimensions(shortSide, shortSide * 4 / 3);
            case "4:5" -> dimensions(shortSide, shortSide * 5 / 4);
            case "5:4" -> dimensions(shortSide * 5 / 4, shortSide);
            case "9:21" -> dimensions(shortSide, shortSide * 21 / 9);
            case "21:9" -> dimensions(shortSide * 21 / 9, shortSide);
            case "1:1" -> dimensions(shortSide, shortSide);
            default -> throw new com.aid.common.exception.ServiceException("视频比例不支持");
        };
    }

    private String dimensions(int width, int height)
    {
        return width + "*" + height;
    }

    private enum Mode { TEXT, IMAGE, REFERENCE, EDIT }
}
