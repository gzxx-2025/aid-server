package com.aid.tokendance.catalog;

import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.billing.model.InputMediaPricing;
import com.aid.billing.model.SettleRule;
import com.aid.billing.model.VideoTokenEstimateRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 将成本快照中口径明确的价格编译为现有 SKU；有歧义的价格保留证据并禁止自动启用。 */
public final class TokenDanceCatalogPricingCompiler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VIDEO_ESTIMATE_RESOURCE =
            "tokendance/catalog/verified-video-token-estimates.json";
    private static final PricingContext DEFAULT_CONTEXT = new PricingContext(loadVideoEstimates());

    private TokenDanceCatalogPricingCompiler() { }

    public record Compilation(BillingRule rule, boolean complete, List<String> warnings) { }

    /** 目录快照对应的视频 Token 估算上下文。 */
    public static final class PricingContext {
        private final Map<String, VerifiedVideoEstimate> videoEstimates;

        private PricingContext(Map<String, VerifiedVideoEstimate> videoEstimates) {
            this.videoEstimates = videoEstimates;
        }
    }

    public static Compilation compile(JsonNode model, String protocol) {
        return compile(model, protocol, DEFAULT_CONTEXT);
    }

    public static Compilation compile(JsonNode model, String protocol, PricingContext context) {
        BillingRule rule = new BillingRule();
        rule.setMode("SKU");
        rule.setPreHold(true);
        rule.setMatchStrategy("FIRST_HIT");
        rule.setSkus(new ArrayList<>());
        List<String> warnings = new ArrayList<>();
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : model.path("items")) {
            String id = item.path("id").asText();
            if (id.startsWith(protocol + ":") || (protocol.equals("wan3:video-synthesis") && id.equals("wan3:video-seconds"))
                    || (protocol.equals("happyhorse:video-synthesis") && id.equals("happyhorse:video-seconds"))) items.add(item);
        }
        ResolvedItems resolvedItems = resolveCompatibleItems(model, protocol, items);
        items = resolvedItems.items();
        boolean token = protocol.equals("openai:chat-completions") || protocol.equals("openai:responses")
                || protocol.equals("anthropic:messages") || protocol.equals("seedance:generations");
        PricingContext resolvedContext = context == null ? DEFAULT_CONTEXT : context;
        if (protocol.equals("seedance:generations")) {
            compileVideoTokens(model, protocol, rule, items, warnings, resolvedContext.videoEstimates);
        }
        else if (token) compileTokens(rule, items, warnings);
        else if (protocol.equals("minimax:voice_clone")) compileClone(rule, items, warnings);
        else compileMedia(rule, items, warnings);
        // BillingSku.priority 的 Java 默认值是 0，但管理端结构化编辑契约从 1 开始。
        // 目录编译结果必须显式生成稳定且唯一的优先级，避免多档 SKU 全部落成 0 后
        // 被管理端判为非法，也避免 FIRST_HIT 在相同优先级下依赖数组偶然顺序。
        assignStablePriorities(rule);
        if (rule.getSkus().isEmpty()) warnings.add("没有可安全转换的成本 SKU");
        if (resolvedItems.remark() != null) {
            rule.getSkus().forEach(sku -> {
                if (sku.getRemark() == null || sku.getRemark().isBlank()) sku.setRemark(resolvedItems.remark());
            });
        }
        if (rule.getMeterType() == null) rule.setMeterType(token ? "TOKEN" : "SKU_PACKAGE");
        boolean hasEnabledSku = rule.getSkus().stream().anyMatch(BillingSku::isEnabled);
        return new Compilation(rule, warnings.isEmpty() && hasEnabledSku, List.copyOf(warnings));
    }

    public static PricingContext createContext(JsonNode root) {
        return new PricingContext(loadVideoEstimates(root));
    }

    private static void assignStablePriorities(BillingRule rule) {
        List<BillingSku> skus = rule.getSkus();
        if (skus == null) return;
        for (int index = 0; index < skus.size(); index++) {
            skus.get(index).setPriority(index + 1);
        }
    }

    private static void compileTokens(BillingRule rule, List<JsonNode> items, List<String> warnings) {
        rule.setMeterType("TOKEN");
        SettleRule settlement = new SettleRule();
        settlement.setUsageSource("PROVIDER_USAGE");
        settlement.setUsagePricingMode("BUCKETED");
        settlement.setSettleMode("REFUND_ONLY");
        settlement.setAllowRefund(true);
        settlement.setAllowExtraCharge(false);
        settlement.setCharToTokenRatio(2);
        rule.setSettleRule(settlement);
        if (items.stream().anyMatch(item -> {
            for (JsonNode plan : item.path("plans")) if (plan.hasNonNull("range")) return true;
            return false;
        })) {
            compileTokenTiers(rule, items, warnings);
            return;
        }
        BillingSku sku = sku("tokens", "Token 用量", Map.of(), "TOKEN");
        for (JsonNode item : items) {
            String bucket = item.path("id").asText();
            bucket = bucket.substring(bucket.lastIndexOf(':') + 1);
            JsonNode plan = flatPlan(item);
            if (plan == null || !"millionTokens".equals(plan.path("unit").asText())) {
                warnings.add("Token 阶梯边界或计量口径待核验：" + bucket);
                continue;
            }
            BigDecimal rate = rate(plan);
            if (rate == null) { warnings.add("成本价格缺失：" + bucket); continue; }
            switch (bucket) {
                case "input_tokens", "prompt_tokens", "uncached_input_tokens" -> sku.setInputPricePerMillion(rate);
                case "output_tokens", "completion_tokens", "text_output_tokens" -> sku.setOutputPricePerMillion(rate);
                case "cached_tokens", "cache_read_input_tokens" -> sku.setCachedInputPricePerMillion(rate);
                case "cache_creation_input_tokens" -> sku.setCacheWritePricePerMillion(rate);
                default -> warnings.add("计量分桶待适配：" + bucket);
            }
        }
        // 输出计量视频本身没有输入 token 费用，零值来自明确的独立输出计量契约。
        if (items.size() == 1 && items.get(0).path("id").asText().equals("seedance:generations:completion_tokens")) {
            sku.setInputPricePerMillion(BigDecimal.ZERO);
            warnings.add("视频 Token 预估规则需逐模型核验");
        }
        if (sku.getInputPricePerMillion() == null || sku.getOutputPricePerMillion() == null) {
            warnings.add("输入或输出 Token 成本不完整");
        } else rule.getSkus().add(sku);
    }

    /**
     * TokenDance 目录展示以首档 ≤ upper、末档 > lower 表达区间，kTokens 为十进制千。
     * 转成系统闭区间时后一档从 lower * 1000 + 1 开始，避免 FIRST_HIT 在边界重叠。
     * 每个输入、输出及缓存桶分别检查覆盖，再合并断点；不按数组下标拼接价格。
     */
    private static void compileTokenTiers(BillingRule rule, List<JsonNode> items, List<String> warnings) {
        Map<String, List<TokenTier>> buckets = new LinkedHashMap<>();
        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(0L);
        boundaries.add((long) Integer.MAX_VALUE + 1);
        int warningCount = warnings.size();
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            String bucket = tokenBucket(id.substring(id.lastIndexOf(':') + 1));
            if (bucket == null || buckets.containsKey(bucket)) {
                warnings.add("Token 分桶未知或重复：" + id);
                continue;
            }
            List<TokenTier> tiers = new ArrayList<>();
            for (JsonNode plan : item.path("plans")) {
                try {
                    BigDecimal price = rate(plan);
                    if (price == null || !"millionTokens".equals(plan.path("unit").asText())) {
                        throw new IllegalArgumentException("单价缺失或不是 millionTokens");
                    }
                    long min = 0;
                    long max = Integer.MAX_VALUE;
                    JsonNode range = plan.path("range");
                    if (!range.isMissingNode() && !range.isNull()) {
                        String selector = range.path("item").asText();
                        String protocol = id.substring(0, id.lastIndexOf(':'));
                        String expected = protocol.equals("openai:chat-completions")
                                ? protocol + ":prompt_tokens" : protocol + ":input_tokens";
                        if (!range.isObject() || !"kTokens".equals(range.path("unit").asText())
                                || !expected.equals(selector)) {
                            throw new IllegalArgumentException("阶梯依据缺失或不是该协议输入 Token 总量");
                        }
                        long lower = tokenBoundary(range.get("lower"));
                        long upper = tokenBoundary(range.get("upper"));
                        min = lower == 0 ? 0 : Math.addExact(lower, 1);
                        max = upper == 0 ? Integer.MAX_VALUE : upper;
                        if (min > max) throw new IllegalArgumentException("区间下限大于上限");
                    }
                    tiers.add(new TokenTier(min, max, price));
                    boundaries.add(min);
                    boundaries.add(max + 1);
                } catch (IllegalArgumentException | ArithmeticException exception) {
                    warnings.add(id + "：" + exception.getMessage());
                }
            }
            if (tiers.isEmpty()) warnings.add("Token 成本档位为空：" + id);
            buckets.put(bucket, tiers);
        }
        if (!buckets.containsKey("input") || !buckets.containsKey("output")) {
            warnings.add("输入或输出 Token 成本不完整");
        }
        if (warnings.size() != warningCount) return;
        List<Long> points = new ArrayList<>(boundaries);
        List<BillingSku> compiled = new ArrayList<>();
        long coveredEnd = -1;
        for (int index = 0; index + 1 < points.size(); index++) {
            long min = points.get(index);
            long max = points.get(index + 1) - 1;
            boolean any = buckets.values().stream().flatMap(List::stream)
                    .anyMatch(tier -> tier.min() <= min && tier.max() >= max);
            if (!any) continue;
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            for (Map.Entry<String, List<TokenTier>> entry : buckets.entrySet()) {
                List<TokenTier> matches = entry.getValue().stream()
                        .filter(tier -> tier.min() <= min && tier.max() >= max).toList();
                if (matches.size() != 1) {
                    warnings.add("输入 Token " + min + "–" + max + " 的 " + entry.getKey()
                            + (matches.isEmpty() ? " 成本缺档" : " 成本区间重叠"));
                } else prices.put(entry.getKey(), matches.get(0).price());
            }
            if (min != coveredEnd + 1) warnings.add("输入 Token 成本区间不连续：" + (coveredEnd + 1) + "–" + (min - 1));
            coveredEnd = max;
            BillingSku sku = sku("tokens_" + min + "_" + max, "输入 Token " + min + "–" + max,
                    Map.of("inputTokensMin", min, "inputTokensMax", max), "TOKEN");
            sku.setInputPricePerMillion(prices.get("input"));
            sku.setOutputPricePerMillion(prices.get("output"));
            sku.setCachedInputPricePerMillion(prices.get("cacheRead"));
            sku.setCacheWritePricePerMillion(prices.get("cacheWrite"));
            sku.setReasoningPricePerMillion(prices.get("reasoning"));
            compiled.add(sku);
        }
        if (warnings.size() == warningCount) rule.getSkus().addAll(compiled);
    }

    private static long tokenBoundary(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        long value = new BigDecimal(node.asText()).multiply(BigDecimal.valueOf(1000)).longValueExact();
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Token 边界超出有效范围");
        return value;
    }

    private static String tokenBucket(String bucket) {
        return switch (bucket) {
            case "input_tokens", "prompt_tokens", "uncached_input_tokens" -> "input";
            case "output_tokens", "completion_tokens", "text_output_tokens" -> "output";
            case "cached_tokens", "cache_read_input_tokens" -> "cacheRead";
            case "cache_creation_input_tokens" -> "cacheWrite";
            case "reasoning_tokens" -> "reasoning";
            default -> null;
        };
    }

    private record TokenTier(long min, long max, BigDecimal price) { }

    /**
     * TokenDance 对同一模型的兼容协议按同一模型成本结算，部分详情页只展示一套价格项。
     * 这里仅在目标协议完全没有价格项时转换已经存在的同模型价格，不覆盖目标协议已公布的任何价格。
     */
    private static ResolvedItems resolveCompatibleItems(JsonNode model, String protocol, List<JsonNode> directItems) {
        boolean unusableImageAlias = "openai:image-generations".equals(protocol)
                && directItems.stream().noneMatch(item -> {
                    for (JsonNode plan : item.path("plans")) {
                        if ("images".equals(plan.path("unit").asText()) && rate(plan) != null) return true;
                    }
                    return false;
                });
        if (!directItems.isEmpty() && !unusableImageAlias) return new ResolvedItems(directItems, null);
        if ("openai:responses".equals(protocol)) {
            List<JsonNode> translated = translateTokenItems(model, "openai:chat-completions", protocol, false);
            if (!translated.isEmpty()) {
                return new ResolvedItems(translated,
                        "TokenDance 未重复列出 Responses 输入输出成本，沿用同模型公开输入输出成本；未单列的缓存按普通输入保守结算");
            }
        }
        if ("anthropic:messages".equals(protocol)) {
            List<JsonNode> translated = translateTokenItems(model, "openai:chat-completions", protocol, true);
            if (!translated.isEmpty()) {
                return new ResolvedItems(translated,
                        "TokenDance 未重复列出 Messages 输入输出成本，沿用同模型公开输入输出成本；未单列的缓存按普通输入保守结算");
            }
        }
        if ("openai:image-generations".equals(protocol)) {
            for (JsonNode item : model.path("items")) {
                if ("ark:image-generations:generated_images".equals(item.path("id").asText())) {
                    ObjectNode copy = item.deepCopy();
                    copy.put("id", "openai:image-generations:images");
                    return new ResolvedItems(List.of(copy),
                            "TokenDance 未重复列出 OpenAI 图片协议成本，沿用同模型 Ark 每张成本");
                }
            }
        }
        return new ResolvedItems(directItems, null);
    }

    private static List<JsonNode> translateTokenItems(
            JsonNode model, String sourceProtocol, String targetProtocol, boolean anthropic) {
        JsonNode input = findItem(model, sourceProtocol + ":input_tokens");
        JsonNode output = findItem(model, sourceProtocol + ":completion_tokens");
        if (input == null || output == null) return List.of();
        List<JsonNode> translated = new ArrayList<>();
        translated.add(translateTokenItem(input, targetProtocol + ":"
                + (anthropic ? "input_tokens" : "uncached_input_tokens"), targetProtocol + ":input_tokens"));
        translated.add(translateTokenItem(output, targetProtocol + ":output_tokens",
                targetProtocol + ":input_tokens"));
        return List.copyOf(translated);
    }

    private static JsonNode findItem(JsonNode model, String id) {
        for (JsonNode item : model.path("items")) {
            if (id.equals(item.path("id").asText())) return item;
        }
        return null;
    }

    private static ObjectNode translateTokenItem(JsonNode source, String targetId, String selector) {
        ObjectNode copy = source.deepCopy();
        copy.put("id", targetId);
        ArrayNode plans = (ArrayNode) copy.withArray("plans");
        for (JsonNode plan : plans) {
            if (plan instanceof ObjectNode object) {
                JsonNode range = object.path("range");
                if (range instanceof ObjectNode rangeObject) rangeObject.put("item", selector);
            }
        }
        return copy;
    }

    private record ResolvedItems(List<JsonNode> items, String remark) { }

    private static void compileMedia(BillingRule rule, List<JsonNode> items, List<String> warnings) {
        int primaryItems = 0;
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            if (id.equals("minimax:video_generation_v2:images")) {
                compileRangedInputImages(rule, item, warnings);
                continue;
            }
            if (id.endsWith(":input_images")) {
                JsonNode plan = flatPlan(item);
                if (plan == null || !"images".equals(plan.path("unit").asText()) || rate(plan) == null) {
                    warnings.add("图片输入附加费待核验");
                } else {
                    InputMediaPricing inputPricing = new InputMediaPricing();
                    InputMediaPricing.ImagePricing image = new InputMediaPricing.ImagePricing();
                    image.setUnitPrice(rate(plan));
                    image.setFreeCount(0);
                    inputPricing.setImage(image);
                    rule.setInputPricing(inputPricing);
                }
                continue;
            }
            primaryItems++;
            JsonNode skus = item.path("skus");
            if (skus.isArray() && !skus.isEmpty()) {
                int index = 0;
                for (JsonNode source : skus) {
                    Map<String, Object> conditions = new LinkedHashMap<>();
                    source.path("specs").fields().forEachRemaining(field -> {
                        String key = switch (field.getKey()) {
                            case "has_video" -> "hasVideoInput";
                            default -> field.getKey();
                        };
                        JsonNode value = field.getValue();
                        if (value.isTextual() && "*".equals(value.asText())) return;
                        if ("feature".equals(key)) {
                            switch (value.asText()) {
                                case "sound" -> { conditions.put("audio", true); conditions.put("hasVideoInput", false); }
                                case "silent" -> { conditions.put("audio", false); conditions.put("hasVideoInput", false); }
                                case "video_reference" -> conditions.put("hasVideoInput", true);
                                default -> warnings.add("费用场景待核验：" + value.asText());
                            }
                            return;
                        }
                        if (value.isBoolean()) conditions.put(key, value.booleanValue());
                        else if (value.isNumber()) conditions.put(key, value.decimalValue());
                        else if (value.isTextual()) conditions.put(key, value.asText());
                        else warnings.add("复杂 SKU 条件待核验：" + key);
                    });
                    BillingSku compiled = mediaSku(source, "component_" + primaryItems + "_tier_" + (++index), conditions, warnings);
                    if (compiled != null) rule.getSkus().add(compiled);
                }
            } else if (hasOutputPixelTiers(item)) {
                compileOutputPixelTiers(rule, item, primaryItems, warnings);
            } else {
                JsonNode plan = flatPlan(item);
                if (plan == null) { warnings.add("费用区间边界待核验：" + id); continue; }
                BillingSku compiled = mediaSku(plan, "component_" + primaryItems + "_standard", Map.of(), warnings);
                if (compiled != null) rule.getSkus().add(compiled);
            }
        }
        if (primaryItems > 1) warnings.add("复合计费项需要独立费用组件，禁止按首个 SKU 少计");
        if (!rule.getSkus().isEmpty()) rule.setMeterType(rule.getSkus().get(0).getMeterType());
    }

    private static void compileRangedInputImages(BillingRule rule, JsonNode item, List<String> warnings) {
        Integer freeCount = null;
        BigDecimal unitPrice = null;
        for (JsonNode plan : item.path("plans")) {
            if (!"images".equals(plan.path("unit").asText()) || rate(plan) == null) {
                warnings.add("图片输入附加费待核验");
                return;
            }
            JsonNode range = plan.path("range");
            if (rate(plan).signum() == 0 && range.path("upper").canConvertToInt()) {
                freeCount = range.path("upper").intValue();
            } else if (rate(plan).signum() > 0 && range.path("lower").canConvertToInt()) {
                if (freeCount == null || range.path("lower").intValue() != freeCount) {
                    warnings.add("图片输入免费区间不连续");
                    return;
                }
                unitPrice = rate(plan);
            }
        }
        if (freeCount == null || freeCount < 0 || unitPrice == null) {
            warnings.add("图片输入阶梯成本不完整");
            return;
        }
        InputMediaPricing inputPricing = rule.getInputPricing();
        if (inputPricing == null) inputPricing = new InputMediaPricing();
        InputMediaPricing.ImagePricing image = new InputMediaPricing.ImagePricing();
        image.setFreeCount(freeCount);
        image.setUnitPrice(unitPrice);
        inputPricing.setImage(image);
        rule.setInputPricing(inputPricing);
    }

    private static boolean hasOutputPixelTiers(JsonNode item) {
        JsonNode plans = item.path("plans");
        if (!plans.isArray() || plans.isEmpty()) return false;
        for (JsonNode plan : plans) {
            JsonNode range = plan.path("range");
            if (!"kPixels".equals(range.path("unit").asText())
                    || !range.path("item").asText().endsWith(":output_pixels")) return false;
        }
        return true;
    }

    private static void compileOutputPixelTiers(
            BillingRule rule, JsonNode item, int component, List<String> warnings) {
        int index = 0;
        for (JsonNode plan : item.path("plans")) {
            JsonNode range = plan.path("range");
            Map<String, Object> match = new LinkedHashMap<>();
            try {
                long lower = pixelBoundary(range.get("lower"));
                long upper = pixelBoundary(range.get("upper"));
                if (lower > 0) match.put("outputPixelsMin", Math.addExact(lower, 1));
                if (upper > 0) match.put("outputPixelsMax", upper);
            } catch (IllegalArgumentException | ArithmeticException exception) {
                warnings.add("输出像素阶梯无效：" + exception.getMessage());
                continue;
            }
            BillingSku sku = mediaSku(plan, "component_" + component + "_pixels_" + (++index), match, warnings);
            if (sku != null) rule.getSkus().add(sku);
        }
    }

    private static long pixelBoundary(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        long value = new BigDecimal(node.asText()).multiply(BigDecimal.valueOf(1000)).longValueExact();
        if (value < 0) throw new IllegalArgumentException("像素边界不能为负数");
        return value;
    }

    private static void compileClone(BillingRule rule, List<JsonNode> items, List<String> warnings) {
        rule.setMeterType("PER_CHAR");
        BillingSku sku = sku("clone_with_preview", "注册音色与试听", Map.of(), "PER_CHAR");
        for (JsonNode item : items) {
            JsonNode plan = flatPlan(item);
            if (plan == null || rate(plan) == null) { warnings.add("克隆成本不完整"); continue; }
            if (item.path("id").asText().endsWith(":counts") && "counts".equals(plan.path("unit").asText())) {
                sku.setFixedSurcharge(rate(plan));
            } else if (item.path("id").asText().endsWith(":characters") && "kCharacters".equals(plan.path("unit").asText())) {
                sku.setPricePerChar(rate(plan).divide(BigDecimal.valueOf(1000)));
            } else warnings.add("克隆计量口径待适配");
        }
        if (sku.getFixedSurcharge() == null || sku.getPricePerChar() == null) warnings.add("克隆或试听成本缺失");
        else rule.getSkus().add(sku);
    }

    private static void compileVideoTokens(
            JsonNode model,
            String protocol,
            BillingRule rule,
            List<JsonNode> items,
            List<String> warnings,
            Map<String, VerifiedVideoEstimate> videoEstimates) {
        rule.setMeterType("TOKEN");
        String modelId = model.path("modelId").asText();
        VerifiedVideoEstimate verified = videoEstimates.get(modelId);
        if (verified == null || !protocol.equals(verified.protocol())) {
            warnings.add("视频 Token 预估规则未核验：" + modelId);
        } else {
            rule.setVideoTokenEstimate(verified.newRule());
            rule.setSettleRule(providerUsageSettlement());
        }
        int index = 0;
        for (JsonNode item : items) {
            if (!item.path("id").asText().equals("seedance:generations:completion_tokens")) {
                warnings.add("视频 Token 计量项待适配");
                continue;
            }
            for (JsonNode source : item.path("skus")) {
                if (!"millionTokens".equals(source.path("unit").asText()) || rate(source) == null) {
                    warnings.add("视频 Token 成本缺失"); continue;
                }
                String resolution = normalize(source.path("specs").path("resolution").asText(null));
                String hasVideo = normalize(source.path("specs").path("has_video").asText(null));
                boolean knownResolution = verified != null && verified.supportedResolutions().contains(resolution);
                boolean explicitlyUnsupported = verified != null
                        && verified.unsupportedResolutions().contains(resolution);
                if (!knownResolution && !explicitlyUnsupported) {
                    warnings.add("视频清晰度未核验：" + source.path("specs").path("resolution").asText("空"));
                }
                if (!("true".equals(hasVideo) || "false".equals(hasVideo))) {
                    warnings.add("输入视频条件无效");
                }
                Map<String, Object> conditions = new LinkedHashMap<>();
                if (source.path("specs").has("resolution")) conditions.put("resolution", source.path("specs").path("resolution").asText());
                if (source.path("specs").has("has_video")) conditions.put("hasVideoInput", source.path("specs").path("has_video").asText());
                BillingSku sku = sku("video_tokens_" + (++index), source.path("description").asText("视频用量"), conditions, "TOKEN");
                sku.setInputPricePerMillion(BigDecimal.ZERO);
                sku.setOutputPricePerMillion(rate(source));
                if (explicitlyUnsupported || !knownResolution
                        || !("true".equals(hasVideo) || "false".equals(hasVideo))) {
                    sku.setEnabled(false);
                }
                if (explicitlyUnsupported) {
                    sku.setRemark("当前官方能力不支持该清晰度；仅保留目录成本证据，禁止启用");
                }
                rule.getSkus().add(sku);
            }
            JsonNode flat = flatPlan(item);
            if (flat != null && "millionTokens".equals(flat.path("unit").asText()) && rate(flat) != null) {
                BillingSku sku = sku("video_tokens_" + (++index), "视频用量", Map.of(), "TOKEN");
                sku.setInputPricePerMillion(BigDecimal.ZERO);
                sku.setOutputPricePerMillion(rate(flat));
                if (verified == null) {
                    sku.setEnabled(false);
                }
                rule.getSkus().add(sku);
            }
        }
    }

    private static SettleRule providerUsageSettlement() {
        SettleRule settlement = new SettleRule();
        settlement.setUsageSource("PROVIDER_USAGE");
        settlement.setUsagePricingMode("BUCKETED");
        settlement.setSettleMode("REFUND_ONLY");
        settlement.setAllowRefund(true);
        settlement.setAllowExtraCharge(false);
        settlement.setCharToTokenRatio(2);
        return settlement;
    }

    private static Map<String, VerifiedVideoEstimate> loadVideoEstimates() {
        try (InputStream stream = new ClassPathResource(VIDEO_ESTIMATE_RESOURCE).getInputStream()) {
            return loadVideoEstimates(MAPPER.readTree(stream));
        } catch (IOException exception) {
            throw new IllegalStateException("视频估算资源无效", exception);
        }
    }

    private static Map<String, VerifiedVideoEstimate> loadVideoEstimates(JsonNode root) {
        LinkedHashMap<String, VerifiedVideoEstimate> result = new LinkedHashMap<>();
        if (root == null || !root.isObject()
                || !"MANUAL_REVIEW_REQUIRED".equals(root.path("defaultActivationPolicy").asText())
                || root.path("autoActivationAllowed").asBoolean(true)) {
            throw new IllegalStateException("视频估算资源无效");
        }
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("视频估算资源无效");
        }
        for (JsonNode model : models) {
            VerifiedVideoEstimate estimate = parseVideoEstimate(model);
            if (result.putIfAbsent(estimate.modelId(), estimate) != null) {
                throw new IllegalStateException("视频估算模型重复");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static VerifiedVideoEstimate parseVideoEstimate(JsonNode model) {
        String modelId = requiredText(model, "modelId");
        String protocol = requiredText(model, "protocol");
        if (!"VERIFIED".equals(requiredText(model, "evidenceStatus"))) {
            throw new IllegalStateException("视频估算未核验");
        }
        Set<String> supported = readNormalizedSet(model.path("supportedCatalogResolutions"));
        Set<String> unsupported = readNormalizedSet(model.path("unsupportedCatalogResolutions"));
        if (supported.isEmpty() || supported.stream().anyMatch(unsupported::contains)) {
            throw new IllegalStateException("视频清晰度配置无效");
        }
        if (!unsupported.isEmpty()
                && !"DISABLE_SKU".equals(model.path("unsupportedCatalogSkuPolicy").asText())) {
            throw new IllegalStateException("视频SKU策略无效");
        }
        JsonNode ruleNode = model.path("videoTokenEstimate");
        if (!ruleNode.isObject()) {
            throw new IllegalStateException("视频估算规则无效");
        }
        VideoTokenEstimateRule rule;
        try {
            rule = MAPPER.treeToValue(ruleNode, VideoTokenEstimateRule.class);
        } catch (Exception exception) {
            throw new IllegalStateException("视频估算规则无效", exception);
        }
        validateVideoRule(rule, supported);
        return new VerifiedVideoEstimate(
                modelId,
                protocol,
                supported,
                unsupported,
                ((ObjectNode) ruleNode).deepCopy());
    }

    private static void validateVideoRule(VideoTokenEstimateRule rule, Set<String> supportedResolutions) {
        if (rule == null || !"PIXEL_FPS".equalsIgnoreCase(rule.getStrategy())
                || !positive(rule.getFramesPerSecond()) || !positive(rule.getTokenDivisor())
                || !positive(rule.getAutoDurationMaxSeconds()) || !positive(rule.getInputVideoMaxSeconds())
                || !positive(rule.getMinimumInputSecondsNumerator())
                || !positive(rule.getMinimumInputSecondsDenominator())
                || rule.getDimensions() == null || rule.getDimensions().isEmpty()) {
            throw new IllegalStateException("视频估算规则无效");
        }
        Set<String> dimensionResolutions = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, int[]>> resolution : rule.getDimensions().entrySet()) {
            dimensionResolutions.add(normalize(resolution.getKey()));
            Map<String, int[]> ratios = resolution.getValue();
            if (ratios == null || !ratios.containsKey("default")) {
                throw new IllegalStateException("视频尺寸配置无效");
            }
            for (int[] dimensions : ratios.values()) {
                if (dimensions == null || dimensions.length != 2
                        || dimensions[0] <= 0 || dimensions[1] <= 0) {
                    throw new IllegalStateException("视频尺寸配置无效");
                }
            }
        }
        if (!dimensionResolutions.equals(supportedResolutions)
                || !dimensionResolutions.contains(normalize(rule.getFallbackResolution()))) {
            throw new IllegalStateException("视频清晰度配置无效");
        }
    }

    private static Set<String> readNormalizedSet(JsonNode array) {
        if (array.isMissingNode() || array.isNull()) {
            return Set.of();
        }
        if (!array.isArray()) {
            throw new IllegalStateException("视频清晰度配置无效");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : array) {
            String normalized = normalize(value.asText(null));
            if (normalized == null || !result.add(normalized)) {
                throw new IllegalStateException("视频清晰度配置无效");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String requiredText(JsonNode parent, String fieldName) {
        String value = parent.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("视频估算资源无效");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static BillingSku mediaSku(JsonNode plan, String code, Map<String, Object> match, List<String> warnings) {
        String unit = plan.path("unit").asText();
        String meter = switch (unit) {
            case "images" -> "PER_IMAGE";
            case "seconds" -> "PER_SECOND";
            case "kCharacters" -> "PER_CHAR";
            case "counts" -> "SKU_PACKAGE";
            default -> null;
        };
        BigDecimal rate = rate(plan);
        if (meter == null || rate == null) { warnings.add("成本单位或金额待核验：" + unit); return null; }
        BillingSku sku = sku(code, plan.path("description").asText("标准费用"), match, meter);
        if (meter.equals("PER_SECOND")) sku.setPricePerSecond(rate);
        else if (meter.equals("PER_CHAR")) sku.setPricePerChar(rate.divide(BigDecimal.valueOf(1000)));
        else sku.setPrice(rate);
        return sku;
    }

    private static BillingSku sku(String code, String name, Map<String, Object> match, String meter) {
        BillingSku sku = new BillingSku();
        sku.setSkuCode(code);
        sku.setSkuName(name);
        sku.setEnabled(true);
        sku.setMeterType(meter);
        sku.setMatch(new LinkedHashMap<>(match));
        return sku;
    }

    private static JsonNode flatPlan(JsonNode item) {
        JsonNode plans = item.path("plans");
        return plans.isArray() && plans.size() == 1 && !plans.get(0).hasNonNull("range") ? plans.get(0) : null;
    }

    private static BigDecimal rate(JsonNode plan) {
        try {
            BigDecimal value = new BigDecimal(plan.path("rate").asText());
            return value.signum() >= 0 ? value : null;
        } catch (NumberFormatException ex) { return null; }
    }

    private record VerifiedVideoEstimate(
            String modelId,
            String protocol,
            Set<String> supportedResolutions,
            Set<String> unsupportedResolutions,
            ObjectNode ruleTemplate) {

        private VideoTokenEstimateRule newRule() {
            try {
                return MAPPER.treeToValue(ruleTemplate, VideoTokenEstimateRule.class);
            } catch (Exception exception) {
                throw new IllegalStateException("视频估算规则无效", exception);
            }
        }
    }
}
