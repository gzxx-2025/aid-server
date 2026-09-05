package com.aid.media.util;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在任务落库前按显式能力规则校验输入组合与图片输出数量。 */
@Slf4j
public final class ModelInputCapabilityValidator {

    private static final String KEY_REQUIRED_INPUTS = "requiredInputs";
    private static final String KEY_REQUIRED_ANY_OF = "requiredAnyOf";
    private static final String KEY_ALLOWED_INPUTS = "allowedInputs";
    private static final String KEY_ALLOWED_SCENES = "allowedScenes";
    private static final String[] IMAGE_LIST_KEYS = {
            "referenceImages", "images", "keyImages", "key_images", "image_settings", "imageSettings"
    };
    private static final String[] VIDEO_SINGLE_KEYS = {
            "featureVideoUrl", "referenceVideoUrl", "baseVideoUrl", "inputVideoUrl", "videoUrl", "video_url"
    };
    private static final String[] VIDEO_LIST_KEYS = {"referenceVideos", "videos"};
    private static final String[] LAST_FRAME_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};

    private ModelInputCapabilityValidator() {
    }

    public static void validateRawImageInputs(AiModelConfigVo modelConfig,
                                              MediaImageGenerateRequest request) {
        if (modelConfig == null || request == null || !hasRawImageInput(request)) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        if (Integer.valueOf(0).equals(configuredInteger(capability, "maxReferenceImages"))) {
            return;
        }
        if (Boolean.FALSE.equals(modelConfig.getSupportsImageInput())) {
            reject(modelConfig, "raw", "模型禁止图片输入", "模型不支持图片");
        }
    }

    public static void validateRawVideoInputs(AiModelConfigVo modelConfig,
                                              MediaVideoGenerateRequest request) {
        if (modelConfig == null || request == null) {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        Map<String, Object> options = request.getOptions();
        boolean hasLastFrame = StrUtil.isNotBlank(firstText(options, LAST_FRAME_KEYS));
        if (hasLastFrame && Boolean.FALSE.equals(modelConfig.getSupportsLastFrame())) {
            reject(modelConfig, "raw", "模型禁止尾帧", "模型不支持尾帧");
        }
        boolean firstFrameSemantic = isFirstFrameSemantic(modelConfig, capability, hasLastFrame);
        if (firstFrameSemantic && StrUtil.isNotBlank(request.getImageUrl())
                && Boolean.FALSE.equals(modelConfig.getSupportsFirstFrame())) {
            reject(modelConfig, "raw", "模型禁止首帧", "模型不支持首帧");
        }
        boolean rawImages = hasRawVideoImageInput(request);
        if (rawImages && !Integer.valueOf(0).equals(configuredInteger(capability, "maxReferenceImages"))
                && Boolean.FALSE.equals(modelConfig.getSupportsImageInput())) {
            reject(modelConfig, "raw", "模型禁止图片输入", "模型不支持图片");
        }
        boolean lipSync = options != null && options.containsKey("video_url") && options.containsKey("audio_url");
        boolean rawVideos = hasRawVideoInput(options, false);
        boolean rawReferenceVideos = hasRawVideoInput(options, lipSync);
        JsonNode supportsVideo = capability == null ? null : capability.get("supportsVideoInput");
        boolean videoForbidden = supportsVideo != null && supportsVideo.isBoolean() && !supportsVideo.asBoolean();
        if ((rawVideos && videoForbidden)
                || (rawReferenceVideos
                && Integer.valueOf(0).equals(configuredInteger(capability, "maxReferenceVideos")))) {
            reject(modelConfig, "raw", "模型禁止视频输入", "模型不支持视频");
        }
    }

    public static void normalizeAndValidateImage(AiModelConfigVo modelConfig,
                                                 MediaImageGenerateRequest request) {
        if (modelConfig == null || request == null) {
            return;
        }
        validateTextCapability(modelConfig);
        normalizeImageOutputCount(modelConfig, request);
        JsonNode capability = capabilityOrMissing(modelConfig);
        validateImageSizeCapability(modelConfig, request, capability);
        InputState inputs = imageInputs(request);
        validateRule(modelConfig, inputs, capability, "root");
        String scene = resolveImageScene(modelConfig, request, inputs);
        validateScene(modelConfig, capability, scene);
        JsonNode sceneRule = capability.path("sceneRules").path(scene);
        validateRule(modelConfig, inputs, sceneRule, "scene");
        validateDefaultInputRequirement(modelConfig, inputs, capability, sceneRule, scene);
    }

    public static void validateVideo(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (modelConfig == null || request == null) {
            return;
        }
        validateTextCapability(modelConfig);
        JsonNode capability = capabilityOrMissing(modelConfig);
        validateVideoParameterCapabilities(modelConfig, request);
        InputState inputs = videoInputs(modelConfig, capability, request);
        validateMinimum(capability, "minReferenceVideos", inputs.videoCount(), "至少传%d个视频");
        validateMinimum(capability, "minReferenceAudios", inputs.audioCount(), "至少传%d个音频");
        validateRule(modelConfig, inputs, capability, "root");
        String scene = resolveVideoScene(modelConfig, capability, inputs);
        validateScene(modelConfig, capability, scene);
        JsonNode sceneRule = capability.path("sceneRules").path(scene);
        validateRule(modelConfig, inputs, sceneRule, "scene");
        validateDefaultInputRequirement(modelConfig, inputs, capability, sceneRule, scene);
    }

    private static void normalizeImageOutputCount(AiModelConfigVo modelConfig,
                                                  MediaImageGenerateRequest request) {
        Integer requested = request.getExpectedImageCount();
        Object optionCount = request.getOptions() == null ? null : request.getOptions().get("n");
        if (requested == null && optionCount != null) {
            requested = parseInteger(optionCount);
        }
        if (requested != null && requested <= 0) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "生成数量无效");
        }
        int normalized = ImageBillingCapabilityHelper.normalizeExpectedCount(
                modelConfig.getModelCode(), requested, modelConfig.getMaxOutputCount());
        if (requested != null && requested > normalized) {
            log.warn("图片输出数量超过模型上限按现有规则截断: modelCode={}, max={}, actual={}",
                    modelConfig.getModelCode(), normalized, requested);
        }
        request.setExpectedImageCount(normalized);
        if (request.getOptions() != null && request.getOptions().containsKey("n")) {
            request.getOptions().put("n", normalized);
        }
    }

    private static void validateTextCapability(AiModelConfigVo modelConfig) {
        if (Boolean.FALSE.equals(modelConfig.getSupportsTextInput())) {
            reject(modelConfig, "parameter", "全局prompt必填但模型禁止文本", "模型能力配置冲突");
        }
    }

    private static void validateImageSizeCapability(AiModelConfigVo modelConfig,
                                                    MediaImageGenerateRequest request,
                                                    JsonNode capability) {
        if (!Boolean.FALSE.equals(modelConfig.getSupportsSizePreset())) {
            return;
        }
        Map<String, Object> options = request.getOptions();
        String optionSize = firstText(options, "resolution", "imageSize", "image_size");
        if (StrUtil.isNotBlank(optionSize)) {
            reject(modelConfig, "parameter", "模型禁止规格档位", "模型不支持规格");
        }
        String size = request.getSize();
        boolean customDimensions = StrUtil.isNotBlank(size)
                && size.trim().matches("(?i)^\\d{2,5}\\s*[*x×]\\s*\\d{2,5}$")
                && capability != null && capability.path("allowCustomWH").asBoolean(false);
        if (StrUtil.isNotBlank(size) && !customDimensions) {
            reject(modelConfig, "parameter", "模型禁止规格档位", "模型不支持规格");
        }
    }

    private static void validateVideoParameterCapabilities(AiModelConfigVo modelConfig,
                                                           MediaVideoGenerateRequest request) {
        if (Boolean.FALSE.equals(modelConfig.getSupportsDuration())
                && request.getDurationSeconds() != null) {
            reject(modelConfig, "parameter", "模型禁止时长参数", "模型不支持时长");
        }
        String size = firstText(request.getOptions(), "resolution", "size", "imageSize", "image_size");
        if (Boolean.FALSE.equals(modelConfig.getSupportsSizePreset()) && StrUtil.isNotBlank(size)) {
            reject(modelConfig, "parameter", "模型禁止规格档位", "模型不支持规格");
        }
    }

    private static void validateRule(AiModelConfigVo modelConfig, InputState inputs,
                                     JsonNode rule, String source) {
        if (rule == null || !rule.isObject()) {
            return;
        }
        if ((rule.has("enabled") && !rule.path("enabled").asBoolean(true))
                || (rule.has("supported") && !rule.path("supported").asBoolean(true))) {
            reject(modelConfig, source, "场景未开启", "输入组合不支持");
        }
        validateInputRequirement(modelConfig, inputs, rule, source);
        Set<String> allowed = readInputNames(rule.get(KEY_ALLOWED_INPUTS));
        if (!allowed.isEmpty() && inputs.actualTypes().stream().anyMatch(type -> !isAllowed(type, allowed))) {
            reject(modelConfig, source, "存在禁止输入", "输入组合不支持");
        }
        for (String required : readInputNames(rule.get(KEY_REQUIRED_INPUTS))) {
            if (!inputs.has(required)) {
                reject(modelConfig, source, "缺少" + required, missingMessage(required));
            }
        }
        validateRequiredAnyOf(modelConfig, inputs, rule.get(KEY_REQUIRED_ANY_OF), source);
    }

    private static void validateScene(AiModelConfigVo modelConfig, JsonNode capability, String scene) {
        Set<String> allowedScenes = readTextValues(capability.get(KEY_ALLOWED_SCENES));
        if (!allowedScenes.isEmpty() && allowedScenes.stream().noneMatch(value -> sameScene(value, scene))) {
            reject(modelConfig, "scene", "场景不在allowedScenes", "输入组合不支持");
        }
        JsonNode sceneRules = capability.path("sceneRules");
        if (capability.path("strictSceneRules").asBoolean(false)
                && sceneRules.isObject() && !sceneRules.has(scene)) {
            reject(modelConfig, "scene", "严格场景未声明:" + scene, "输入组合不支持");
        }
    }

    private static void validateRequiredAnyOf(AiModelConfigVo modelConfig, InputState inputs,
                                              JsonNode node, String source) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return;
        }
        boolean nestedGroups = false;
        for (JsonNode item : node) {
            if (item.isArray()) {
                nestedGroups = true;
                Set<String> group = readInputNames(item);
                if (!group.isEmpty() && group.stream().noneMatch(inputs::has)) {
                    reject(modelConfig, source, "任一输入组未满足", "缺少必要输入");
                }
            }
        }
        if (!nestedGroups) {
            Set<String> group = readInputNames(node);
            if (!group.isEmpty() && group.stream().noneMatch(inputs::has)) {
                reject(modelConfig, source, "任一输入未满足", "缺少必要输入");
            }
        }
    }

    private static void validateMinimum(JsonNode capability, String key, int actual, String template) {
        JsonNode node = capability.get(key);
        if (node != null && node.isNumber() && node.intValue() > 0 && actual < node.intValue()) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, String.format(template, node.intValue()));
        }
    }

    private static String resolveImageScene(AiModelConfigVo modelConfig,
                                            MediaImageGenerateRequest request, InputState inputs) {
        Map<String, Object> options = request.getOptions();
        if (options != null && Boolean.parseBoolean(String.valueOf(options.get("enable_sequential")))) {
            return "sequentialImage";
        }
        String mode = normalizeMode(modelConfig.getGenerateMode());
        boolean hasImage = inputs.has("image");
        if (!hasImage && declaresScene(
                ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson()), "textToImage")) {
            return "textToImage";
        }
        if ("image_edit".equals(mode)) return "imageEdit";
        if ("image_upscale".equals(mode)) return "imageUpscale";
        if ("image_to_image".equals(mode)) return "imageToImage";
        return inputs.has("image") ? "imageToImage" : "textToImage";
    }

    private static String resolveVideoScene(AiModelConfigVo modelConfig, JsonNode capability,
                                            InputState inputs) {
        String configured = ModelCapabilityResolver.readText(capability, "videoScenario");
        String configuredScene = scenarioToScene(configured);
        if (StrUtil.isNotBlank(configuredScene)) {
            return configuredScene;
        }
        JsonNode scenes = capability.path("sceneRules");
        String mode = normalizeMode(modelConfig.getGenerateMode());
        boolean hasMedia = inputs.has("image") || inputs.has("video") || inputs.has("audio");
        if (!hasMedia && declaresScene(capability, "textToVideo")) return "textToVideo";
        if (inputs.has("lastFrame") || "first_last_frame".equals(mode)
                || "start_end".equals(mode) || "start_end_to_video".equals(mode)) return "startEndToVideo";
        if (inputs.has("firstFrame")) return "imageToVideo";
        // 全模态模型常用一个 model_code 同时承载首帧和参考生视频；有真实参考媒体且能力只声明
        // referenceToVideo 时，应让实际输入覆盖静态 generateMode=image_to_video。
        if ((inputs.referenceImageCount() > 0 || inputs.referenceVideoCount() > 0
                || inputs.referenceAudioCount() > 0)
                && declaresScene(capability, "referenceToVideo")
                && !declaresScene(capability, "videoToVideo")) {
            return "referenceToVideo";
        }
        if (("image_to_video".equals(mode) || "first_frame".equals(mode))
                && !isReferenceImageRole(capability)) return "imageToVideo";
        if ("reference_to_video".equals(mode) || "reference".equals(mode)) return "referenceToVideo";
        if ("video_to_video".equals(mode) || "video_edit".equals(mode)
                || "video_extend".equals(mode) || "edit".equals(mode)
                || "extend".equals(mode) || "lip_sync".equals(mode)) return "videoToVideo";
        if (inputs.referenceImageCount() > 0 || inputs.referenceVideoCount() > 0
                || inputs.referenceAudioCount() > 0) {
            return "referenceToVideo";
        }
        if (inputs.has("video")) return "videoToVideo";
        if (inputs.has("image") && scenes.has("referenceToVideo") && !scenes.has("imageToVideo")) {
            return "referenceToVideo";
        }
        if (inputs.has("image")) return "imageToVideo";
        if (!inputs.has("audio")) return "textToVideo";
        return "referenceToVideo";
    }

    private static void validateInputRequirement(AiModelConfigVo modelConfig, InputState inputs,
                                                 JsonNode rule, String source) {
        String requirement = normalizeMode(ModelCapabilityResolver.readText(rule, "inputRequirement"));
        switch (requirement) {
            case "text_only" -> {
                if (inputs.has("image") || inputs.has("video") || inputs.has("audio")) {
                    reject(modelConfig, source, "纯文本场景带媒体", "输入组合不支持");
                }
            }
            case "image_required" -> requireInput(modelConfig, inputs, "image", source);
            case "video_required" -> requireInput(modelConfig, inputs, "video", source);
            default -> {
                // 缺省及 image_optional 保持兼容；安全默认由实际场景补齐。
            }
        }
    }

    private static void validateDefaultInputRequirement(AiModelConfigVo modelConfig, InputState inputs,
                                                        JsonNode capability, JsonNode sceneRule,
                                                        String scene) {
        if (hasExplicitInputRequirement(capability) || hasExplicitInputRequirement(sceneRule)) {
            return;
        }
        if ("lip_sync".equals(normalizeMode(modelConfig == null ? null : modelConfig.getGenerateMode()))) {
            requireInput(modelConfig, inputs, "video", "generateMode");
            requireInput(modelConfig, inputs, "audio", "generateMode");
            return;
        }
        switch (scene) {
            case "imageToImage", "imageEdit", "imageUpscale", "imageToVideo" ->
                    requireInput(modelConfig, inputs, "image", "generateMode");
            case "startEndToVideo" -> {
                requireInput(modelConfig, inputs, "firstFrame", "generateMode");
                requireInput(modelConfig, inputs, "lastFrame", "generateMode");
            }
            case "videoToVideo" -> requireInput(modelConfig, inputs, "video", "generateMode");
            case "referenceToVideo" -> {
                if (!inputs.has("image") && !inputs.has("video") && !inputs.has("audio")) {
                    reject(modelConfig, "generateMode", "参考模式缺少媒体", "缺少必要输入");
                }
            }
            default -> {
                // 纯文本场景没有额外媒体必填项。
            }
        }
    }

    private static boolean hasExplicitInputRequirement(JsonNode rule) {
        return rule != null && rule.isObject()
                && (rule.has(KEY_REQUIRED_INPUTS) || rule.has(KEY_REQUIRED_ANY_OF)
                || rule.has("inputRequirement"));
    }

    private static void requireInput(AiModelConfigVo modelConfig, InputState inputs,
                                     String input, String source) {
        if (!inputs.has(input)) {
            reject(modelConfig, source, "缺少" + input, missingMessage(input));
        }
    }

    private static boolean declaresScene(JsonNode capability, String scene) {
        if (capability == null || StrUtil.isBlank(scene)) {
            return false;
        }
        if (readTextValues(capability.get(KEY_ALLOWED_SCENES)).stream()
                .anyMatch(value -> sameScene(value, scene))) {
            return true;
        }
        JsonNode sceneRules = capability.path("sceneRules");
        if (!sceneRules.isObject()) {
            return false;
        }
        java.util.Iterator<String> names = sceneRules.fieldNames();
        while (names.hasNext()) {
            if (sameScene(names.next(), scene)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode capabilityOrMissing(AiModelConfigVo modelConfig) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(
                modelConfig == null ? null : modelConfig.getCapabilityJson());
        return capability == null ? MissingNode.getInstance() : capability;
    }

    private static String scenarioToScene(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        return switch (normalizeMode(raw)) {
            case "text", "text_to_video", "texttovideo" -> "textToVideo";
            case "first_frame", "firstframe", "image_to_video", "imagetovideo" -> "imageToVideo";
            case "first_last_frame", "firstlastframe", "start_end_to_video", "startendtovideo" -> "startEndToVideo";
            case "reference", "reference_to_video", "referencetovideo" -> "referenceToVideo";
            case "edit", "extend", "video_to_video", "videotovideo" -> "videoToVideo";
            default -> raw.trim();
        };
    }

    private static InputState imageInputs(MediaImageGenerateRequest request) {
        Set<String> images = new LinkedHashSet<>();
        addUrl(images, request.getReferenceImageUrl());
        Map<String, Object> options = request.getOptions();
        addUrls(images, options == null ? null : options.get("referenceImages"));
        addUrls(images, options == null ? null : options.get("images"));
        return new InputState(StrUtil.isNotBlank(request.getPrompt()), false, false,
                images.size(), 0, 0, images.isEmpty() ? 0 : images.size(), 0, 0);
    }

    private static InputState videoInputs(AiModelConfigVo modelConfig, JsonNode capability,
                                          MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        Set<String> referenceImages = new LinkedHashSet<>();
        String lastFrame = firstText(options, LAST_FRAME_KEYS);
        boolean hasLastFrame = StrUtil.isNotBlank(lastFrame);
        for (String key : IMAGE_LIST_KEYS) {
            addUrls(referenceImages, options == null ? null : options.get(key));
        }
        Set<String> videos = new LinkedHashSet<>();
        for (String key : VIDEO_SINGLE_KEYS) addUrl(videos, options == null ? null : options.get(key));
        for (String key : VIDEO_LIST_KEYS) addUrls(videos, options == null ? null : options.get(key));
        int audioCount = 0;
        List<ReferenceAudioInput> audios = request.getReferenceAudios();
        if (audios != null) {
            audioCount = (int) audios.stream().filter(Objects::nonNull)
                    .map(ReferenceAudioInput::getSampleUrl).filter(StrUtil::isNotBlank).distinct().count();
        }
        Object lipSyncAudio = options == null ? null : options.get("audio_url");
        if (lipSyncAudio != null && StrUtil.isNotBlank(String.valueOf(lipSyncAudio))) {
            audioCount = Math.max(audioCount, 1);
        }
        // 同一模型同时支持首帧与多模态参考时，显式参考图/视频/音频优先表达参考场景；
        // 只有没有其他参考素材（或明确带尾帧）时，顶层 imageUrl 才解释为首帧。
        boolean hasOtherReferenceMedia = !referenceImages.isEmpty() || !videos.isEmpty() || audioCount > 0;
        boolean firstFrame = StrUtil.isNotBlank(request.getImageUrl())
                && isFirstFrameSemantic(modelConfig, capability, hasLastFrame)
                && (hasLastFrame || !hasOtherReferenceMedia);
        if (!firstFrame) {
            addUrl(referenceImages, request.getImageUrl());
        }
        int imageCount = referenceImages.size() + (firstFrame ? 1 : 0) + (hasLastFrame ? 1 : 0);
        return new InputState(StrUtil.isNotBlank(request.getPrompt()), firstFrame, hasLastFrame,
                imageCount, videos.size(), audioCount, referenceImages.size(),
                videos.size(), audioCount);
    }

    private static boolean hasRawImageInput(MediaImageGenerateRequest request) {
        if (StrUtil.isNotBlank(request.getReferenceImageUrl())) return true;
        Map<String, Object> options = request.getOptions();
        return hasNonBlankValue(options == null ? null : options.get("referenceImages"))
                || hasNonBlankValue(options == null ? null : options.get("images"));
    }

    private static boolean hasRawVideoImageInput(MediaVideoGenerateRequest request) {
        if (StrUtil.isNotBlank(request.getImageUrl())) return true;
        Map<String, Object> options = request.getOptions();
        if (StrUtil.isNotBlank(firstText(options, LAST_FRAME_KEYS))) return true;
        for (String key : IMAGE_LIST_KEYS) {
            if (hasNonBlankValue(options == null ? null : options.get(key))) return true;
        }
        return false;
    }

    private static boolean hasRawVideoInput(Map<String, Object> options, boolean lipSync) {
        if (options == null) return false;
        for (String key : VIDEO_SINGLE_KEYS) {
            if (lipSync && ("videoUrl".equals(key) || "video_url".equals(key))) continue;
            if (hasNonBlankValue(options.get(key))) return true;
        }
        for (String key : VIDEO_LIST_KEYS) {
            if (hasNonBlankValue(options.get(key))) return true;
        }
        return false;
    }

    private static boolean hasNonBlankValue(Object raw) {
        if (raw instanceof List<?> list) return list.stream().anyMatch(ModelInputCapabilityValidator::hasNonBlankValue);
        return raw != null && StrUtil.isNotBlank(String.valueOf(raw));
    }

    private static boolean isFirstFrameSemantic(AiModelConfigVo modelConfig, JsonNode capability,
                                                boolean hasLastFrame) {
        if (hasLastFrame) return true;
        String imageRole = normalizeMode(ModelCapabilityResolver.readText(capability, "inputImageRole"));
        if (StrUtil.isNotBlank(imageRole)) {
            return "first_frame".equals(imageRole) || "firstframe".equals(imageRole);
        }
        String scenario = normalizeMode(ModelCapabilityResolver.readText(capability, "videoScenario"));
        if (StrUtil.isNotBlank(scenario)) {
            String scene = scenarioToScene(scenario);
            return "imageToVideo".equals(scene) || "startEndToVideo".equals(scene);
        }
        String mode = normalizeMode(modelConfig == null ? null : modelConfig.getGenerateMode());
        return "image_to_video".equals(mode) || "first_frame".equals(mode)
                || "first_last_frame".equals(mode) || "start_end".equals(mode)
                || "start_end_to_video".equals(mode);
    }

    private static boolean isReferenceImageRole(JsonNode capability) {
        String imageRole = normalizeMode(ModelCapabilityResolver.readText(capability, "inputImageRole"));
        return "reference".equals(imageRole) || "reference_image".equals(imageRole)
                || "referenceimage".equals(imageRole);
    }

    private static Set<String> readInputNames(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node == null) return values;
        if (node.isTextual()) {
            String normalized = normalizeInputName(node.asText());
            if (normalized != null) values.add(normalized);
            return values;
        }
        if (!node.isArray()) return values;
        for (JsonNode item : node) {
            if (item.isTextual()) {
                String normalized = normalizeInputName(item.asText());
                if (normalized != null) values.add(normalized);
            }
        }
        return values;
    }

    private static Set<String> readTextValues(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node == null) return values;
        if (node.isTextual() && StrUtil.isNotBlank(node.asText())) values.add(node.asText().trim());
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && StrUtil.isNotBlank(item.asText())) values.add(item.asText().trim());
            }
        }
        return values;
    }

    private static boolean sameScene(String first, String second) {
        return first != null && second != null
                && first.replace("_", "").replace("-", "").equalsIgnoreCase(
                second.replace("_", "").replace("-", ""));
    }

    private static Integer configuredInteger(JsonNode capability, String key) {
        JsonNode node = capability == null ? null : capability.get(key);
        return node != null && node.isNumber() ? node.intValue() : null;
    }

    private static String normalizeInputName(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        String compact = raw.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
        return switch (compact) {
            case "text", "prompt" -> "text";
            case "image", "images", "referenceimage", "referenceimages" -> "image";
            case "video", "videos", "referencevideo", "referencevideos" -> "video";
            case "audio", "audios", "referenceaudio", "referenceaudios" -> "audio";
            case "firstframe", "startframe", "startimage" -> "firstFrame";
            case "lastframe", "endframe", "endimage" -> "lastFrame";
            default -> raw.trim();
        };
    }

    private static boolean isAllowed(String actual, Set<String> allowed) {
        if (allowed.contains(actual)) return true;
        return ("firstFrame".equals(actual) || "lastFrame".equals(actual)) && allowed.contains("image");
    }

    private static String missingMessage(String input) {
        return switch (input) {
            case "image" -> "缺少图片输入";
            case "video" -> "缺少视频输入";
            case "audio" -> "缺少音频输入";
            case "firstFrame" -> "缺少首帧";
            case "lastFrame" -> "缺少尾帧";
            default -> "缺少必要输入";
        };
    }

    private static void reject(AiModelConfigVo modelConfig, String source,
                               String reason, String clientMessage) {
        log.info("模型输入能力校验拒绝: modelCode={}, source={}, reason={}",
                modelConfig.getModelCode(), source, reason);
        throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, clientMessage);
    }

    private static Integer parseInteger(Object value) {
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "生成数量无效");
        }
    }

    private static String firstText(Map<String, Object> options, String... keys) {
        if (options == null) return null;
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) return String.valueOf(value).trim();
        }
        return null;
    }

    private static void addUrls(Set<String> target, Object raw) {
        if (raw instanceof List<?> list) list.forEach(item -> addUrl(target, item));
        else addUrl(target, raw);
    }

    private static void addUrl(Set<String> target, Object raw) {
        if (raw == null) return;
        if (raw instanceof Map<?, ?> map) {
            for (String key : new String[]{"key_image", "keyImage", "image_url", "imageUrl", "url"}) {
                if (map.containsKey(key)) {
                    addUrl(target, map.get(key));
                    return;
                }
            }
        }
        String value = String.valueOf(raw).trim();
        if (StrUtil.isNotBlank(value)) target.add(value);
    }

    private static String normalizeMode(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private record InputState(boolean text, boolean firstFrame, boolean lastFrame,
                              int imageCount, int videoCount, int audioCount,
                              int referenceImageCount, int referenceVideoCount,
                              int referenceAudioCount) {
        boolean has(String type) {
            return switch (type) {
                case "text" -> text;
                case "image" -> imageCount > 0;
                case "video" -> videoCount > 0;
                case "audio" -> audioCount > 0;
                case "firstFrame" -> firstFrame;
                case "lastFrame" -> lastFrame;
                default -> false;
            };
        }

        Set<String> actualTypes() {
            Set<String> result = new LinkedHashSet<>();
            if (text) result.add("text");
            if (firstFrame) result.add("firstFrame");
            if (lastFrame) result.add("lastFrame");
            if (referenceImageCount > 0) result.add("image");
            if (videoCount > 0) result.add("video");
            if (referenceAudioCount > 0) result.add("audio");
            return result;
        }
    }
}
