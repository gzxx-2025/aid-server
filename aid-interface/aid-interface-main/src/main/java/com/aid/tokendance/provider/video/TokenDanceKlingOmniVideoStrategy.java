package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TokenDance 可灵 Omni Video 请求方言。 */
final class TokenDanceKlingOmniVideoStrategy implements TokenDanceVideoProtocolStrategy
{
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("图片(\\d+)(?:\\[[^]]*])?");
    private static final Pattern CONTENT_REFERENCE = Pattern.compile("@([A-Za-z][A-Za-z0-9_-]{0,63})");
    private static final Pattern ELEMENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Set<String> AUDIO_MODES = Set.of("off", "native", "original");
    private static final Set<String> RESOLUTIONS = Set.of("720p", "1080p", "4k");
    private static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1");
    private static final Set<String> SCENES = Set.of(
            "omni_t2v", "omni_i2v", "omni_first_last", "omni_reference",
            "omni_feature_video", "omni_edit");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.KLING_OMNI_VIDEO;
    }

    @Override
    public String submitPath()
    {
        return TokenDanceEndpoints.KLING_OMNI_SUBMIT;
    }

    @Override
    public String queryTemplate()
    {
        return TokenDanceEndpoints.KLING_OMNI_QUERY;
    }

    @Override
    public TokenDanceVideoResponseMapper.Shape responseShape()
    {
        return TokenDanceVideoResponseMapper.Shape.KLING;
    }

    @Override
    public void sanitizePrompt(MediaVideoGenerateRequest request, TokenDanceVideoInputs inputs)
    {
        int images = inputs.referenceImages.size()
                + (inputs.firstFrame == null ? 0 : 1)
                + (inputs.lastFrame == null ? 0 : 1);
        ReferencePromptSanitizer.sanitizeInPlacePreservingSubjectRefs(request, images, 0);
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request)
    {
        TokenDanceVideoInputs.rejectUnknownRequestOptions(modelConfig, request, Set.of(
                "elements", "klingScenario", "videoScenario", "operation",
                "audioMode", "multi_shot", "multiShot"));
        TokenDanceVideoInputs inputs = TokenDanceVideoInputs.from(modelConfig, request);
        TokenDanceVideoBodySupport.require(StrUtil.isNotBlank(inputs.prompt), "缺少视频描述");
        TokenDanceVideoBodySupport.reject(!inputs.referenceAudios.isEmpty(), "该协议不支持音频");
        List<ElementInput> elements = readElements(inputs.options.get("elements"));
        String scene = resolveScene(modelConfig, inputs, elements);
        validateSceneInputs(scene, inputs, elements);
        boolean edit = "omni_edit".equals(scene);
        boolean hasVideo = !inputs.referenceVideos.isEmpty();

        List<Map<String, Object>> contents = new ArrayList<>();
        List<String> imageIds = new ArrayList<>();
        int imageIndex = 1;
        if (StrUtil.isNotBlank(inputs.firstFrame))
        {
            String id = "image_" + imageIndex++;
            contents.add(TokenDanceVideoBodySupport.content(
                    "first_frame", null, inputs.firstFrame, null, id));
            imageIds.add(id);
        }
        if (StrUtil.isNotBlank(inputs.lastFrame))
        {
            String id = "image_" + imageIndex++;
            contents.add(TokenDanceVideoBodySupport.content(
                    "last_frame", null, inputs.lastFrame, null, id));
            imageIds.add(id);
        }
        for (String url : inputs.referenceImages)
        {
            String id = "image_" + imageIndex++;
            contents.add(TokenDanceVideoBodySupport.content("refer_image", null, url, null, id));
            imageIds.add(id);
        }
        int videoIndex = 1;
        for (String url : inputs.referenceVideos)
        {
            String id = "video_" + videoIndex++;
            contents.add(TokenDanceVideoBodySupport.content(
                    edit ? "base_video" : "feature_video", null, url, null, id));
        }
        int elementIndex = 1;
        Set<String> contentIds = new java.util.LinkedHashSet<>(imageIds);
        if (hasVideo)
        {
            contentIds.add("video_1");
        }
        for (ElementInput element : elements)
        {
            String id = StrUtil.blankToDefault(element.referenceId(), "element_" + elementIndex++);
            TokenDanceVideoBodySupport.require(contentIds.add(id), "素材编号重复");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "element");
            content.put("element_id", element.elementId());
            content.put("id", id);
            contents.add(content);
        }
        contents.add(0, TokenDanceVideoBodySupport.content(
                "prompt", retainKnownReferences(rehydratePrompt(inputs.prompt, imageIds), contentIds),
                null, null, null));

        Map<String, Object> settings = new LinkedHashMap<>();
        String resolution = TokenDanceVideoBodySupport.lowerResolution(inputs.resolution);
        if (resolution != null)
        {
            TokenDanceVideoBodySupport.require(RESOLUTIONS.contains(resolution), "分辨率不支持");
            settings.put("resolution", resolution);
        }
        if (!edit)
        {
            if (inputs.duration != null)
            {
                TokenDanceVideoBodySupport.require(inputs.duration >= 3 && inputs.duration <= 15,
                        "视频时长不支持");
            }
            TokenDanceVideoBodySupport.putIfPresent(settings, "duration", inputs.duration);
        }
        if (hasVideo && StrUtil.isNotBlank(inputs.ratio))
        {
            throw new com.aid.common.exception.ServiceException("视频参考不支持比例");
        }
        if (StrUtil.isNotBlank(inputs.ratio))
        {
            TokenDanceVideoBodySupport.require(ASPECT_RATIOS.contains(inputs.ratio.trim()), "画面比例不支持");
            settings.put("aspect_ratio", inputs.ratio.trim());
        }
        String audioMode = audioMode(inputs, edit);
        settings.put("audio", audioMode);
        Boolean multiShot = TokenDancePayloadSupport.bool(inputs.options, "multi_shot", "multiShot");
        if ("omni_feature_video".equals(scene))
        {
            TokenDanceVideoBodySupport.reject(!"off".equals(audioMode), "视频参考需关闭音频");
            TokenDanceVideoBodySupport.reject(Boolean.FALSE.equals(multiShot), "视频参考需开启多镜头");
            settings.put("multi_shot", true);
        }
        else if (edit)
        {
            TokenDanceVideoBodySupport.reject(Boolean.TRUE.equals(multiShot), "视频编辑不支持多镜头");
            settings.put("multi_shot", false);
        }
        else if (multiShot != null)
        {
            settings.put("multi_shot", multiShot);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_name", inputs.model);
        body.put("contents", contents);
        body.put("settings", settings);
        Map<String, Object> options = TokenDanceVideoBodySupport.watermarkOptions(inputs);
        if (!options.isEmpty())
        {
            body.put("options", options);
        }
        return body;
    }

    private String audioMode(TokenDanceVideoInputs inputs, boolean edit)
    {
        String configured = TokenDancePayloadSupport.text(inputs.options, "audioMode");
        if (StrUtil.isBlank(configured))
        {
            configured = Boolean.TRUE.equals(inputs.generateAudio) ? "native" : "off";
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        TokenDanceVideoBodySupport.require(AUDIO_MODES.contains(normalized), "音频模式不支持");
        TokenDanceVideoBodySupport.reject(!edit && "original".equals(normalized), "音频模式不支持");
        return normalized;
    }

    private String resolveScene(AiModelConfigVo modelConfig, TokenDanceVideoInputs inputs,
            List<ElementInput> elements)
    {
        String configured = TokenDancePayloadSupport.text(inputs.options,
                "klingScenario", "videoScenario", "operation");
        if (StrUtil.isNotBlank(configured))
        {
            String normalized = configured.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if ("edit".equals(normalized)) normalized = "omni_edit";
            TokenDanceVideoBodySupport.require(SCENES.contains(normalized), "可灵生成场景不支持");
            return normalized;
        }
        if (inputs.videoEditMode(modelConfig)) return "omni_edit";
        if (!inputs.referenceVideos.isEmpty()) return "omni_feature_video";
        if (StrUtil.isNotBlank(inputs.firstFrame) && StrUtil.isNotBlank(inputs.lastFrame)) {
            return "omni_first_last";
        }
        if (!inputs.referenceImages.isEmpty() || !elements.isEmpty()) return "omni_reference";
        if (StrUtil.isNotBlank(inputs.firstFrame)) return "omni_i2v";
        return "omni_t2v";
    }

    private void validateSceneInputs(String scene, TokenDanceVideoInputs inputs, List<ElementInput> elements)
    {
        boolean first = StrUtil.isNotBlank(inputs.firstFrame);
        boolean last = StrUtil.isNotBlank(inputs.lastFrame);
        int references = inputs.referenceImages.size();
        int videos = inputs.referenceVideos.size();
        TokenDanceVideoBodySupport.reject(videos > 1, "参考视频最多 1 段");
        switch (scene)
        {
            case "omni_t2v" -> TokenDanceVideoBodySupport.reject(
                    first || last || references > 0 || videos > 0 || !elements.isEmpty(),
                    "文生视频不接受参考素材");
            case "omni_i2v" -> {
                TokenDanceVideoBodySupport.require(first, "图生视频必须提供首帧");
                TokenDanceVideoBodySupport.reject(last || references > 0 || videos > 0 || !elements.isEmpty(),
                        "首帧图生视频不接受其他参考素材");
            }
            case "omni_first_last" -> {
                TokenDanceVideoBodySupport.require(first && last, "首尾帧场景必须同时提供两张图片");
                TokenDanceVideoBodySupport.reject(references > 0 || videos > 0, "首尾帧场景不接受其他参考素材");
                TokenDanceVideoBodySupport.reject(elements.size() > 3, "首尾帧场景最多 3 个主体");
            }
            case "omni_reference" -> {
                TokenDanceVideoBodySupport.require(references > 0 || first || !elements.isEmpty(),
                        "多参考场景必须提供图片或主体");
                TokenDanceVideoBodySupport.reject(videos > 0, "多参考场景不接受视频");
                validateReferenceCombination(references + (first ? 1 : 0) + (last ? 1 : 0), elements);
            }
            case "omni_feature_video", "omni_edit" -> {
                TokenDanceVideoBodySupport.require(videos == 1, "该场景必须提供 1 段参考视频");
                TokenDanceVideoBodySupport.reject(first || last, "视频参考场景不接受首尾帧");
                validateVideoCombination(references, elements);
            }
            default -> throw new IllegalStateException("未支持的可灵场景");
        }
    }

    private void validateReferenceCombination(int imageCount, List<ElementInput> elements)
    {
        int videoCharacters = countElementType(elements, "video_character_elements");
        int multiImageElements = countElementType(elements, "multi_image_elements");
        TokenDanceVideoBodySupport.reject(videoCharacters > 3, "视频角色主体最多 3 个");
        int maxImages = videoCharacters > 0 && (multiImageElements > 0 || imageCount > 0) ? 4 : 7;
        TokenDanceVideoBodySupport.reject(imageCount + multiImageElements > maxImages,
                "参考图片与多图主体组合超限");
    }

    private void validateVideoCombination(int imageCount, List<ElementInput> elements)
    {
        int videoCharacters = countElementType(elements, "video_character_elements");
        int multiImageElements = countElementType(elements, "multi_image_elements");
        TokenDanceVideoBodySupport.reject(videoCharacters > 0 && multiImageElements > 0,
                "有参考视频时不能混用两类主体");
        TokenDanceVideoBodySupport.reject(videoCharacters > 0 && imageCount > 0,
                "视频角色主体不能与参考图片同时使用");
        TokenDanceVideoBodySupport.reject(videoCharacters > 1, "有参考视频时最多 1 个视频角色主体");
        if (videoCharacters == 0)
        {
            TokenDanceVideoBodySupport.reject(imageCount + multiImageElements > 4,
                    "有参考视频时图片与多图主体组合超限");
        }
    }

    private int countElementType(List<ElementInput> elements, String expected)
    {
        int count = 0;
        for (ElementInput element : elements)
        {
            TokenDanceVideoBodySupport.require(Set.of("video_character_elements", "multi_image_elements")
                    .contains(element.elementType()), "主体类型不支持");
            if (expected.equals(element.elementType())) count++;
        }
        return count;
    }

    private List<ElementInput> readElements(Object raw)
    {
        if (raw == null) return List.of();
        TokenDanceVideoBodySupport.require(raw instanceof Iterable<?>, "主体列表无效");
        List<ElementInput> result = new ArrayList<>();
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (Object item : (Iterable<?>) raw)
        {
            TokenDanceVideoBodySupport.require(item instanceof Map<?, ?>, "主体配置无效");
            Map<?, ?> map = (Map<?, ?>) item;
            String elementId = text(map, "element_id", "elementId");
            String referenceId = text(map, "id");
            String elementType = text(map, "element_type", "elementType");
            TokenDanceVideoBodySupport.require(elementId != null && ELEMENT_ID.matcher(elementId).matches(),
                    "主体编号无效");
            if (referenceId != null)
            {
                TokenDanceVideoBodySupport.require(CONTENT_REFERENCE.matcher("@" + referenceId).matches(),
                        "素材编号无效");
                TokenDanceVideoBodySupport.require(ids.add(referenceId), "素材编号重复");
            }
            TokenDanceVideoBodySupport.require(elementType != null, "主体必须声明类型");
            result.add(new ElementInput(elementId, referenceId, elementType.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(result);
    }

    private String text(Map<?, ?> map, String... keys)
    {
        for (String key : keys)
        {
            Object value = map.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) return String.valueOf(value).trim();
        }
        return null;
    }

    private String rehydratePrompt(String prompt, List<String> imageIds)
    {
        if (imageIds.isEmpty())
        {
            return prompt;
        }
        Matcher matcher = IMAGE_REFERENCE.matcher(prompt);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
        {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index > 0 && index <= imageIds.size()
                    ? "@" + imageIds.get(index - 1) : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String retainKnownReferences(String prompt, Set<String> contentIds)
    {
        Matcher matcher = CONTENT_REFERENCE.matcher(prompt);
        StringBuffer result = new StringBuffer();
        while (matcher.find())
        {
            String id = matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    contentIds.contains(id) ? matcher.group() : id));
        }
        matcher.appendTail(result);
        TokenDanceVideoBodySupport.require(result.length() <= 3072, "提示词过长");
        return result.toString();
    }

    private record ElementInput(String elementId, String referenceId, String elementType)
    {
    }
}
