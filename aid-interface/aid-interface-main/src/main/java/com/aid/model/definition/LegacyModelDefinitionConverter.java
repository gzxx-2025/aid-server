package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将旧的模型场景配置转换为模型内能力，保留已配置的协议参数。 */
public final class LegacyModelDefinitionConverter {
    private static final Map<String, String> SCENES = Map.ofEntries(
            Map.entry("textOnly", "text"), Map.entry("textToImage", "text_to_image"),
            Map.entry("imageToImage", "image_to_image"), Map.entry("imageEdit", "image_edit"),
            Map.entry("imageUpscale", "image_upscale"), Map.entry("textToVideo", "text_to_video"),
            Map.entry("imageToVideo", "image_to_video"), Map.entry("startEndToVideo", "start_end_to_video"),
            Map.entry("referenceToVideo", "reference_to_video"), Map.entry("videoToVideo", "video_to_video"));
    private static final Map<String, String> KLING = Map.ofEntries(
            Map.entry("omni_t2v", "text_to_video"), Map.entry("omni_i2v", "image_to_video"),
            Map.entry("standard_i2v", "image_to_video"), Map.entry("turbo_i2v", "image_to_video"),
            Map.entry("omni_first_last", "start_end_to_video"), Map.entry("omni_reference", "reference_to_video"),
            Map.entry("standard_multi", "reference_to_video"), Map.entry("omni_feature_video", "feature_video"),
            Map.entry("omni_edit", "video_edit"));
    private static final Map<String, String> VIDEO_SCENARIOS = Map.ofEntries(
            Map.entry("text_to_video", "text_to_video"), Map.entry("first_frame", "image_to_video"),
            Map.entry("last_frame", "last_frame_to_video"), Map.entry("first_last_frame", "start_end_to_video"),
            Map.entry("multimodal_reference", "reference_to_video"), Map.entry("video_edit", "video_edit"),
            Map.entry("video_extend", "video_extend"));

    private LegacyModelDefinitionConverter() { }

    public static List<ModelCapabilityDefinition> convert(AidAiModel model) {
        JSONObject capability = object(model.getCapabilityJson());
        if (capability.getBooleanValue("lipSync")) {
            var definition = build(model, "lip_sync", capability);
            definition.setDefaultCapability(true);
            return new ArrayList<>(List.of(definition));
        }
        if ("tokendance:minimax:voice_clone".equals(model.getProtocol())) {
            var definition = build(model, "voice_clone", capability);
            definition.setDefaultCapability(true);
            return new ArrayList<>(List.of(definition));
        }
        String selected = KLING.get(capability.getString("klingScenario") == null ? "" : capability.getString("klingScenario"));
        if (selected == null) selected = VIDEO_SCENARIOS.get(capability.getString("videoScenario") == null ? "" : capability.getString("videoScenario"));
        List<ModelCapabilityDefinition> result = new ArrayList<>();
        if (selected != null) {
            JSONObject scenes = capability.getJSONObject("sceneRules");
            if (scenes != null && scenes.size() == 1) {
                Object scene = scenes.values().iterator().next();
                if (scene instanceof Map<?, ?> values) values.forEach((key, value) -> capability.put(String.valueOf(key), value));
            }
            result.add(build(model, selected, capability));
        } else {
            JSONObject scenes = capability.getJSONObject("sceneRules");
            if (scenes != null) for (String scene : scenes.keySet()) {
                if (!SCENES.containsKey(scene)) continue;
                JSONObject scoped = object(model.getCapabilityJson());
                scoped.put("sceneRules", Map.of(scene, scenes.get(scene)));
                if (scenes.get(scene) instanceof Map<?, ?> values) values.forEach((key, value) -> scoped.put(String.valueOf(key), value));
                result.add(build(model, SCENES.get(scene), scoped));
            }
            if (result.isEmpty()) result.add(build(model, primaryCode(model), capability));
        }
        String primary = primaryCode(model);
        if (("standard_multi".equals(capability.getString("klingScenario"))
                || selected == null && result.stream().anyMatch(d -> "image_to_video".equals(d.getCode()))) && Boolean.TRUE.equals(model.getSupportsFirstFrame())
                && Boolean.TRUE.equals(model.getSupportsLastFrame()) && result.stream().noneMatch(d -> "start_end_to_video".equals(d.getCode()))) {
            result.add(build(model, "start_end_to_video", capability));
        }
        ModelCapabilityDefinition preferred = result.stream().filter(d -> d.getCode().equals(primary)).findFirst().orElse(result.get(0));
        preferred.setDefaultCapability(true);
        return result;
    }

    /** 将旧功能池的用途映射到已有能力；特殊协议保留原场景，不按素材数量猜测。 */
    public static String businessCode(AidAiModel model, String functionCode) {
        return businessCode(model, functionCode, null);
    }

    /**
     * 将旧功能池的用途映射到模型实际声明的能力。功能编码负责区分分镜视频的特殊入口，
     * 其余公共功能优先使用功能配置的 generate_mode，避免多能力旧模型总是落到根默认能力。
     */
    public static String businessCode(AidAiModel model, String functionCode, String functionGenerateMode) {
        List<ModelCapabilityDefinition> definitions = convert(model);
        String preferred = switch (functionCode) {
            case "main_storyboard_video_edge" -> "start_end_to_video";
            case "main_storyboard_video_image", "main_storyboard_video_grid" -> "image_to_video";
            case "main_storyboard_video", "main_storyboard_video_multi_pro" -> "reference_to_video";
            default -> normalizeCode(functionGenerateMode);
        };
        return definitions.stream().filter(cap -> preferred != null && preferred.equals(cap.getCode())).findFirst()
                .orElseGet(() -> definitions.stream().filter(cap -> Boolean.TRUE.equals(cap.getDefaultCapability())).findFirst().orElseThrow()).getCode();
    }

    private static String normalizeCode(String value) {
        return value == null || value.isBlank() ? null
                : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static String primaryCode(AidAiModel model) {
        if ("tokendance:minimax:voice_clone".equals(model.getProtocol())) return "voice_clone";
        JSONObject capability = object(model.getCapabilityJson());
        if (capability.getBooleanValue("lipSync")) return "lip_sync";
        String scenario = capability.getString("klingScenario");
        if (KLING.containsKey(scenario == null ? "" : scenario)) return KLING.get(scenario);
        String videoScenario = capability.getString("videoScenario");
        if (VIDEO_SCENARIOS.containsKey(videoScenario == null ? "" : videoScenario)) return VIDEO_SCENARIOS.get(videoScenario);
        String mode = model.getGenerateMode();
        if (mode == null || mode.isBlank()) mode = model.getModelType();
        return mode == null ? "text" : mode.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static ModelCapabilityDefinition build(AidAiModel model, String code, JSONObject capability) {
        ModelCapabilityDefinition definition = new ModelCapabilityDefinition();
        definition.setCode(code);
        definition.setLabel(switch (code) {
            case "text" -> "文本生成";
            case "text_to_image" -> "文生图";
            case "image_to_image" -> "图生图";
            case "image_edit" -> "图片编辑";
            case "image_upscale" -> "图片高清";
            case "text_to_video" -> "文生视频";
            case "image_to_video" -> "首帧图生视频";
            case "last_frame_to_video" -> "尾帧图生视频";
            case "start_end_to_video" -> "首尾帧视频";
            case "reference_to_video" -> "多参考视频";
            case "feature_video" -> "视频特征参考";
            case "video_edit" -> "视频编辑";
            case "lip_sync" -> "对口型";
            case "audio" -> "语音合成";
            case "voice_clone" -> "音色克隆";
            default -> code;
        });
        definition.setGenerateMode(code);
        definition.setEvidenceStatus("LEGACY_PENDING_REVIEW");
        definition.setParameters(LegacyModelParameterConverter.convert(model, capability));
        Map<String, Object> presentation = new LinkedHashMap<>();
        for (String field : ModelInvocationResolver.PRESENTATION_FIELDS) presentation.put(field, BeanUtil.getProperty(model, field));
        if (capability.containsKey("defaultSize")) presentation.put("defaultSizeCode", capability.get("defaultSize"));
        for (String field : ModelInvocationResolver.PRESENTATION_FIELDS) if (capability.containsKey(field)) presentation.put(field, capability.get(field));
        applyScenePresentation(code, capability, presentation);
        definition.setPresentation(presentation);
        ModelProtocolBinding route = new ModelProtocolBinding();
        route.setCode("route_" + model.getId());
        route.setProtocol(model.getProtocol());
        route.setUpstreamModel(model.getRealModelCode() == null || model.getRealModelCode().isBlank() ? model.getModelCode() : model.getRealModelCode());
        route.setApiSuffix(model.getApiSuffix());
        route.setApiVersion(model.getApiVersion());
        route.setDefaultBinding(true);
        route.setCapability(capability);
        route.setPresentation(presentation);
        route.setParameterMapping(object(model.getParamMappingJson()));
        route.setFixedParameters(object(model.getExtraBody()));
        route.setBillingMode(model.getBillingMode());
        route.setBillingRule(object(model.getBillingRuleJson()));
        route.setCostCredits(model.getCostCredits());
        definition.setBindings(new ArrayList<>(List.of(route)));
        return definition;
    }

    /** 场景定义必须覆盖模型顶层的首尾帧开关，避免多场景模型把其他场景的素材能力串入当前调用。 */
    private static void applyScenePresentation(String code, JSONObject capability, Map<String, Object> presentation) {
        if (!Set.of("text_to_video", "image_to_video", "last_frame_to_video", "start_end_to_video",
                "reference_to_video", "video_to_video", "feature_video", "video_edit", "video_extend").contains(code)) return;
        Set<String> declared = new java.util.HashSet<>();
        for (String field : List.of("requiredInputs", "allowedInputs")) {
            Object raw = capability.get(field);
            if (raw instanceof List<?> values) values.forEach(value -> declared.add(String.valueOf(value)));
        }
        boolean first = declared.contains("firstFrame") || "image_to_video".equals(code) || "start_end_to_video".equals(code);
        boolean last = declared.contains("lastFrame") || "last_frame_to_video".equals(code) || "start_end_to_video".equals(code);
        presentation.put("supportsFirstFrame", first);
        presentation.put("supportsLastFrame", last);
    }

    private static JSONObject object(String raw) { return raw == null || raw.isBlank() ? new JSONObject() : JSON.parseObject(raw); }
}
