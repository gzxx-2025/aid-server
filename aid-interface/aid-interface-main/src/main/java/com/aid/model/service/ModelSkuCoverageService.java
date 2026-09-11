package com.aid.model.service;

import com.aid.aid.domain.AidAiModel;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.billing.service.BillingRuleResolver;
import com.aid.billing.util.BillingRouteDimensions;
import com.aid.billing.util.ResolutionUtil;
import com.aid.billing.util.SkuMatchUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.util.ModelCapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只检查能力声明的有限计费组合；未知或无限参数域明确返回待核验，不生成价格。 */
@Service
@RequiredArgsConstructor
public class ModelSkuCoverageService {
    private static final int MAX_CASES = 2048;
    private static final Set<String> AXES = Set.of("resolution", "duration", "audio", "imageCount", "expectedImageCount",
            "referenceImageCount", "referenceVideoCount", "inputVideoCount", "referenceAudioCount",
            "inputTokens", "outputPixels");
    private static final Set<String> ROUTE = Set.of("protocol", "operation", "scene", "generateMode", "hasVideoInput");
    private final BillingRuleResolver resolver;

    public Report inspect(AidAiModel model) {
        if (!"SKU".equalsIgnoreCase(model.getBillingMode())) return new Report("NOT_APPLICABLE", 0, List.of(), List.of(), List.of());
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode(model.getModelCode());
        config.setRealModelCode(model.getRealModelCode());
        config.setProtocol(model.getProtocol());
        config.setProviderCode(model.getProtocol() != null && model.getProtocol().startsWith("tokendance:") ? "tokendance" : null);
        config.setBillingMode(model.getBillingMode());
        config.setBillingRuleJson(model.getBillingRuleJson());
        BillingRule rule = resolver.parseRule(config);
        if (rule == null) return new Report("GAPS", 0, List.of(Map.of("reason", "计费规则缺失")), List.of(), List.of());
        List<BillingSku> configuredEnabled = rule.getSkus() == null ? List.of() : rule.getSkus().stream()
                .filter(BillingSku::isEnabled)
                .sorted(Comparator.comparingInt(BillingSku::getPriority))
                .toList();
        Set<String> keys = new LinkedHashSet<>();
        configuredEnabled.forEach(sku -> { if (sku.getMatch() != null) sku.getMatch().keySet().forEach(key -> keys.add(baseKey(key))); });
        List<String> warnings = new ArrayList<>();
        List<BillingSku> enabled = new ArrayList<>();
        for (BillingSku sku : configuredEnabled) {
            String invalidReason = invalidPriceReason(rule, sku);
            if (invalidReason == null) enabled.add(sku);
            else warnings.add("启用 SKU 价格无效：" + skuName(sku) + "（" + invalidReason + "）");
        }
        for (String key : keys) if (!AXES.contains(key) && !ROUTE.contains(key)) warnings.add("需按实际请求核验条件：" + key);
        JsonNode capability = ModelCapabilityResolver.parseCapability(model.getCapabilityJson());
        if (capability == null) return new Report("REVIEW_REQUIRED", 0, List.of(), List.of(), List.of("模型能力尚未配置"));
        String type = model.getModelType() == null ? "" : model.getModelType().toUpperCase(Locale.ROOT);
        List<CoverageScene> scenes = scenes(model, capability, type);
        warnUnhandledCombinationConstraints(capability, scenes, warnings);
        Set<String> sampledKeys = new LinkedHashSet<>(keys);
        if (keys.contains("hasVideoInput") && !keys.contains("referenceVideoCount")
                && !keys.contains("inputVideoCount")) {
            // hasVideoInput 是 referenceVideoCount/inputVideoCount 的运行时派生维度。即使 SKU
            // 没直接声明数量条件，也必须按能力域枚举 0 与正数，不能把参考场景恒定当成 false。
            sampledKeys.add("referenceVideoCount");
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        Set<String> conflicts = new LinkedHashSet<>();
        int checked = 0;
        for (CoverageScene scene : scenes) {
            JsonNode local = sceneRule(capability, scene.capabilityKey());
            Map<String, Object> initial = new LinkedHashMap<>();
            if (Set.of("IMAGE", "VIDEO").contains(type)) {
                if (scene.runtimeGenerateMode() == null) {
                    warnings.add("场景缺少运行时计费映射：" + scene.capabilityKey());
                } else {
                    initial.put("generateMode", scene.runtimeGenerateMode());
                }
            }
            initial.put("hasVideoInput", "VIDEO_TO_VIDEO".equals(scene.runtimeGenerateMode()));
            BillingInput route = new BillingInput(type, initial);
            BillingRouteDimensions.apply(config, route);
            List<Map<String, Object>> cases = new ArrayList<>();
            cases.add(route.getParams());
            for (String key : sampledKeys) {
                if (!AXES.contains(key)) continue;
                if ("expectedImageCount".equals(key) && sampledKeys.contains("imageCount")
                        || "inputVideoCount".equals(key) && sampledKeys.contains("referenceVideoCount")) continue;
                List<Object> values = Set.of("inputTokens", "outputPixels").contains(key)
                        ? numericBoundaryValues(key, capability, configuredEnabled, sampledKeys, warnings)
                        : values(key, model, capability, local, scene, warnings);
                if (values.isEmpty()) continue;
                if ((long) cases.size() * values.size() + checked > MAX_CASES) {
                    warnings.add("组合超过检查上限，请按场景缩小配置后复核");
                    break;
                }
                List<Map<String, Object>> expanded = new ArrayList<>();
                for (Map<String, Object> sample : cases) for (Object value : values) {
                    Map<String, Object> next = new LinkedHashMap<>(sample);
                    next.put(key, value);
                    if (Set.of("imageCount", "expectedImageCount").contains(key)) { next.put("imageCount", value); next.put("expectedImageCount", value); }
                    if (Set.of("inputVideoCount", "referenceVideoCount").contains(key)) { next.put("inputVideoCount", value); next.put("referenceVideoCount", value); }
                    if (Set.of("inputVideoCount", "referenceVideoCount").contains(key) && value instanceof Number count) {
                        next.put("hasVideoInput", count.intValue() > 0);
                    }
                    expanded.add(next);
                }
                cases = expanded;
            }
            for (Map<String, Object> sample : cases) {
                if (checked >= MAX_CASES) break;
                // 尚未求解的条件不能用缺失值参与匹配，否则会把未知误报为缺价。
                if (keys.stream().anyMatch(key -> !sample.containsKey(key))) {
                    warnings.add("存在未解析的计费维度，缺价结论需按完整请求复核");
                    continue;
                }
                checked++;
                List<BillingSku> matches = enabled.stream().filter(sku -> SkuMatchUtil.isMatch(sku.getMatch(), sample)).toList();
                if (matches.isEmpty()) {
                    if (missing.size() < 50) missing.add(new LinkedHashMap<>(sample));
                } else if (matches.size() > 1 && matches.get(0).getPriority() == matches.get(1).getPriority()) {
                    conflicts.add(matches.get(0).getSkuCode() + " / " + matches.get(1).getSkuCode());
                }
            }
        }
        String status = !warnings.isEmpty() ? "REVIEW_REQUIRED" : !missing.isEmpty() ? "GAPS" : conflicts.isEmpty() ? "COMPLETE" : "CONFLICTS";
        return new Report(status, checked, missing, List.copyOf(conflicts), List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private List<Object> numericBoundaryValues(String key, JsonNode capability, List<BillingSku> skus,
                                               Set<String> axes, List<String> warnings) {
        if ("outputPixels".equals(key) && axes.contains("resolution")) {
            warnings.add("像素与清晰度有关联，需按模型尺寸映射核验，不能独立组合");
            return List.of();
        }
        long min = "outputPixels".equals(key) ? capability.path("minOutputPixels").asLong(1) : 1;
        long max = "outputPixels".equals(key) ? capability.path("maxOutputPixels").asLong(0)
                : capability.path("maxInputTokens").asLong(capability.path("contextWindowTokens").asLong(0));
        if (max < min) {
            warnings.add("未声明完整数值能力域：" + key);
            return List.of();
        }
        Set<Long> points = new java.util.TreeSet<>();
        points.add(min);
        points.add(max);
        for (BillingSku sku : skus) {
            if (sku.getMatch() == null) continue;
            for (String condition : List.of(key, key + "Min", key + "Max")) {
                Object raw = sku.getMatch().get(condition);
                if (raw == null) continue;
                try {
                    long boundary = new BigDecimal(String.valueOf(raw)).longValueExact();
                    for (int offset : List.of(-1, 0, 1)) {
                        long point = Math.addExact(boundary, offset);
                        if (point >= min && point <= max) points.add(point);
                    }
                } catch (ArithmeticException | NumberFormatException ex) {
                    warnings.add("数值边界无法解析：" + condition);
                }
            }
        }
        return new ArrayList<>(points);
    }

    private List<Object> values(String key, AidAiModel model, JsonNode root, JsonNode local,
                                CoverageScene scene, List<String> warnings) {
        if ("resolution".equals(key) || "duration".equals(key)) {
            String field = "resolution".equals(key) ? "sizeOptions" : "durationOptions";
            JsonNode options = local.has(field) ? local.path(field) : root.path(field);
            List<Object> values = new ArrayList<>();
            if (options.isArray()) for (JsonNode option : options) {
                if (option.isTextual() || option.isNumber()) {
                    if ("duration".equals(key)) {
                        try { int value = Integer.parseInt(option.asText()); if (value > 0) values.add(value); else warnings.add("自适应时长需按运行快照核验"); }
                        catch (NumberFormatException ex) { warnings.add("时长候选无法转换为请求秒数"); }
                    } else {
                        String tier = ResolutionUtil.parseTier(option.asText());
                        values.add(tier == null ? ResolutionUtil.parseResolution(option.asText()) : tier);
                    }
                }
            }
            if (values.isEmpty()) warnings.add("能力未声明可检查的" + ("resolution".equals(key) ? "清晰度" : "时长") + "候选");
            if ("resolution".equals(key) && root.path("allowCustomWH").asBoolean()) warnings.add("自定义尺寸需按实际像素复核价格覆盖");
            return List.copyOf(new LinkedHashSet<>(values));
        }
        if ("audio".equals(key)) return root.path("supportsAudio").asBoolean() ? List.of(false, true) : List.of(false);
        int min = 0;
        int max;
        switch (key) {
            case "imageCount", "expectedImageCount" -> { min = 1; max = model.getMaxOutputCount() == null ? 1 : model.getMaxOutputCount(); }
            case "referenceImageCount" -> {
                boolean imageScene = Set.of("imagetoimage", "imageedit", "imageupscale", "imagetovideo",
                        "startendtovideo", "referencetovideo").contains(normalizeScene(scene.capabilityKey()));
                if (!imageScene) return List.of(0);
                min = switch (normalizeScene(scene.capabilityKey())) {
                    case "startendtovideo" -> 2;
                    case "referencetovideo" -> integer(local, root, "minReferenceImages", 0);
                    default -> 1;
                };
                max = integer(local, root, "maxReferenceImages", -1);
            }
            case "referenceVideoCount", "inputVideoCount" -> {
                String normalized = normalizeScene(scene.capabilityKey());
                if (!Set.of("videotovideo", "referencetovideo").contains(normalized)) return List.of(0);
                min = "videotovideo".equals(normalized) ? 1 : integer(local, root, "minReferenceVideos", 0);
                max = integer(local, root, "maxReferenceVideos", -1);
            }
            case "referenceAudioCount" -> {
                if (!"referencetovideo".equals(normalizeScene(scene.capabilityKey()))) return List.of(0);
                min = integer(local, root, "minReferenceAudios", 0);
                max = integer(local, root, "maxReferenceAudios", 0);
            }
            default -> { return List.of(); }
        }
        if (max < min || max > 50) { warnings.add("需复核素材数量域：" + key); return List.of(); }
        List<Object> values = new ArrayList<>();
        for (int value = min; value <= max; value++) values.add(value);
        return values;
    }

    private List<CoverageScene> scenes(AidAiModel model, JsonNode capability, String type) {
        if (!Set.of("IMAGE", "VIDEO").contains(type)) {
            String operation = type.equals("AUDIO") ? "SYNTHESIZE" : "TEXT_GENERATE";
            return List.of(new CoverageScene(operation, operation));
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (JsonNode scene : capability.path("allowedScenes")) {
            if (scene.isTextual() && !scene.asText().isBlank()) {
                declared.putIfAbsent(normalizeScene(scene.asText()), scene.asText().trim());
            }
        }
        JsonNode sceneRules = capability.path("sceneRules");
        if (sceneRules.isObject()) {
            sceneRules.fieldNames().forEachRemaining(scene ->
                    declared.putIfAbsent(normalizeScene(scene), scene));
        }
        if (declared.isEmpty()) {
            String defaultScene = type.equals("IMAGE") ? "textToImage" : "textToVideo";
            declared.put(normalizeScene(defaultScene), defaultScene);
            if (Boolean.TRUE.equals(model.getSupportsImageInput())) {
                String imageScene = type.equals("IMAGE") ? "imageToImage" : "imageToVideo";
                declared.put(normalizeScene(imageScene), imageScene);
            }
            if (type.equals("VIDEO") && capability.path("supportsVideoInput").asBoolean()) {
                declared.put(normalizeScene("videoToVideo"), "videoToVideo");
            }
        }
        return declared.values().stream()
                .map(scene -> new CoverageScene(scene, runtimeGenerateMode(type, scene)))
                .toList();
    }

    private JsonNode sceneRule(JsonNode capability, String scene) {
        JsonNode rules = capability.path("sceneRules");
        if (!rules.isObject()) return rules.path(scene);
        JsonNode exact = rules.get(scene);
        if (exact != null) return exact;
        java.util.Iterator<String> fields = rules.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (normalizeScene(field).equals(normalizeScene(scene))) return rules.path(field);
        }
        return rules.path(scene);
    }

    /**
     * 当前检查器只覆盖有限离散轴，不能把尚未求解的素材依赖/互斥约束误报为完整覆盖。
     * 这些字段仍由运行时能力校验器负责拒绝；覆盖报告保守标为 REVIEW_REQUIRED。
     */
    private void warnUnhandledCombinationConstraints(JsonNode capability, List<CoverageScene> scenes,
                                                     List<String> warnings) {
        warnUnhandledRuleConstraints(capability, "全局", warnings);
        for (CoverageScene scene : scenes) {
            warnUnhandledRuleConstraints(sceneRule(capability, scene.capabilityKey()),
                    "场景 " + scene.capabilityKey(), warnings);
        }
    }

    private void warnUnhandledRuleConstraints(JsonNode rule, String source, List<String> warnings) {
        if (rule == null || !rule.isObject()) return;
        List<String> fields = new ArrayList<>();
        addPresentConstraint(rule, fields, "requiredInputs");
        addPresentConstraint(rule, fields, "requiredAnyOf");
        addPresentConstraint(rule, fields, "allowedInputs");
        addPresentConstraint(rule, fields, "inputRequirement");
        addPresentConstraint(rule, fields, "maxReferenceMaterials");
        if (rule.path("referenceAudioRequiresGeneratedAudio").asBoolean(false)) {
            fields.add("referenceAudioRequiresGeneratedAudio");
        }
        addPresentConstraint(rule, fields, "mutuallyExclusiveInputs");
        addPresentConstraint(rule, fields, "inputDependencies");
        addPresentConstraint(rule, fields, "mixedMediaRules");
        addPresentConstraint(rule, fields, "combinationRules");
        if (!fields.isEmpty()) {
            warnings.add(source + "存在有限采样未求解的组合约束：" + String.join("、", fields));
        }
    }

    private static void addPresentConstraint(JsonNode rule, List<String> fields, String field) {
        JsonNode value = rule.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return;
        if (value.isArray() || value.isObject()) {
            if (!value.isEmpty()) fields.add(field);
            return;
        }
        if (value.isTextual()) {
            if (!value.asText().isBlank()) fields.add(field);
            return;
        }
        if (value.isNumber()) {
            if (value.decimalValue().signum() >= 0) fields.add(field);
            return;
        }
        if (value.asBoolean(false)) fields.add(field);
    }

    /** 与运行时计算器的主价格门禁保持一致；合法零价仍是有效价格。 */
    private static String invalidPriceReason(BillingRule rule, BillingSku sku) {
        if (sku == null) return "SKU 为空";
        if (hasNegativePrice(sku)) return "价格不能为负数";
        MeterType meter = MeterType.of(sku.getMeterType() == null || sku.getMeterType().isBlank()
                ? rule.getMeterType() : sku.getMeterType());
        if (meter == null) return "计费口径无效";
        boolean explicitMeter = sku.getMeterType() != null && !sku.getMeterType().isBlank();
        boolean valid = switch (meter) {
            case TOKEN -> nonNegative(sku.getInputPricePerMillion())
                    && nonNegative(sku.getOutputPricePerMillion());
            case PER_IMAGE, SKU_PACKAGE -> nonNegative(sku.getPrice());
            case PER_SECOND -> nonNegative(sku.getPricePerSecond())
                    || (!explicitMeter && positive(sku.getPrice()) && positiveDurationMax(sku.getMatch()));
            case PER_CHAR -> nonNegative(sku.getPricePerChar())
                    || (!explicitMeter && positive(sku.getPrice()));
        };
        if (!valid) return "缺少 " + meter + " 主价格";
        BigDecimal surcharge = sku.getFixedSurcharge();
        if (surcharge != null && meter != MeterType.PER_CHAR && meter != MeterType.SKU_PACKAGE) {
            return "该计费口径不支持固定附加费";
        }
        return null;
    }

    private static boolean hasNegativePrice(BillingSku sku) {
        return java.util.stream.Stream.of(sku.getPrice(), sku.getPricePerSecond(), sku.getPricePerChar(),
                        sku.getFixedSurcharge(), sku.getInputPricePerMillion(), sku.getOutputPricePerMillion(),
                        sku.getCachedInputPricePerMillion(), sku.getCacheWritePricePerMillion(),
                        sku.getReasoningPricePerMillion())
                .anyMatch(value -> value != null && value.signum() < 0);
    }

    private static boolean nonNegative(BigDecimal value) { return value != null && value.signum() >= 0; }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    private static boolean positiveDurationMax(Map<String, Object> match) {
        if (match == null || match.get("durationMax") == null) return false;
        try { return new BigDecimal(String.valueOf(match.get("durationMax"))).signum() > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static String skuName(BillingSku sku) {
        return sku == null || sku.getSkuCode() == null || sku.getSkuCode().isBlank()
                ? "未命名" : sku.getSkuCode();
    }

    private static String runtimeGenerateMode(String type, String scene) {
        String normalized = normalizeScene(scene);
        if ("IMAGE".equals(type)) {
            return switch (normalized) {
                case "texttoimage" -> "TEXT_TO_IMAGE";
                case "imagetoimage", "imageedit" -> "IMAGE_EDIT";
                case "imageupscale" -> "UPSCALE";
                default -> null;
            };
        }
        return switch (normalized) {
            case "texttovideo" -> "TEXT_TO_VIDEO";
            case "imagetovideo" -> "IMAGE_TO_VIDEO";
            case "startendtovideo" -> "EDGE_TO_VIDEO";
            case "referencetovideo" -> "MULTI_TO_VIDEO";
            case "videotovideo" -> "VIDEO_TO_VIDEO";
            default -> null;
        };
    }

    private static String normalizeScene(String scene) {
        return scene == null ? "" : scene.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private int integer(JsonNode local, JsonNode root, String key, int fallback) { return local.has(key) ? local.path(key).asInt(fallback) : root.path(key).asInt(fallback); }
    private static String baseKey(String key) { return key.endsWith("Min") || key.endsWith("Max") ? key.substring(0, key.length() - 3) : key; }

    private record CoverageScene(String capabilityKey, String runtimeGenerateMode) { }

    public record Report(String status, int checkedCombinations, List<Map<String, Object>> missingCombinations,
                         List<String> conflicts, List<String> warnings) { }
}
