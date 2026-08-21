package com.aid.billing.service.impl;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.util.BillingInputExtractor;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.KlingVideoRequestBuilder;
import com.aid.media.util.ModelCapabilityValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KlingPricingContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String migrationSql;
    private static String initSql;

    @BeforeAll
    static void loadSql() throws Exception {
        migrationSql = Files.readString(findRepoFile("sql/v1.0.0-beta.6.sql"), StandardCharsets.UTF_8);
        initSql = Files.readString(findRepoFile("sql/aid-init.sql"), StandardCharsets.UTF_8);
    }

    @Test
    void migrationAndInitUseIdenticalOfficialPricingRules() throws Exception {
        for (String variable : Set.of("kling_turbo_billing_rule", "kling_standard_billing_rule",
            "kling_omni_no_reference_video_billing_rule", "kling_omni_reference_video_billing_rule")) {
            JsonNode migrationRule = rule(migrationSql, variable);
            JsonNode initRule = rule(initSql, variable);
            assertEquals(migrationRule, initRule, variable);
            assertSkuIntegrity(migrationRule);
        }
        String priceUrl = "https://klingai.com/document-api/pricing/base/video";
        assertTrue(migrationSql.contains(priceUrl));
        assertTrue(initSql.contains(priceUrl));
        assertTrue(migrationSql.contains("`official_price_url`=VALUES(`official_price_url`)"));
    }

    @Test
    void migrationPreservesOperatorKlingProxyWhileInitKeepsOfficialDefault() {
        Pattern preserveConfiguredBaseUrl = Pattern.compile(
            "`base_url`=CASE\\s+WHEN NULLIF\\(TRIM\\(`base_url`\\),''\\) IS NULL "
                + "THEN VALUES\\(`base_url`\\)\\s+ELSE `base_url`\\s+END");

        assertTrue(preserveConfiguredBaseUrl.matcher(migrationSql).find());
        assertTrue(initSql.contains("'kling',NULL,'https://api-beijing.klingai.com'"));
        assertFalse(initSql.contains("api.bananarouter.com"));
    }

    @Test
    void everySceneUsesTheExpectedUnambiguousRuleAndRemainsDisabled() {
        Map<String, String> expected = Map.of(
            "kling-3.0-turbo-i2v", "@kling_turbo_billing_rule",
            "kling-3.0-i2v", "@kling_standard_billing_rule",
            "kling-3.0-multi", "@kling_standard_billing_rule",
            "kling-3.0-omni-t2v", "@kling_omni_no_reference_video_billing_rule",
            "kling-3.0-omni-i2v", "@kling_omni_no_reference_video_billing_rule",
            "kling-3.0-omni-first-last", "@kling_omni_no_reference_video_billing_rule",
            "kling-3.0-omni-reference", "@kling_omni_no_reference_video_billing_rule",
            "kling-3.0-omni-feature-video", "@kling_omni_reference_video_billing_rule",
            "kling-3.0-omni-edit", "@kling_omni_reference_video_billing_rule");
        Matcher matcher = Pattern.compile(
            "(?m)^\\(10[0-8],21,'([^']+)'[^\\r\\n]*?,'kling-video',100,'1','0'[^\\r\\n]*?,'SKU',(@[a-z0-9_]+_billing_rule)")
            .matcher(initSql);
        Map<String, String> actual = new HashMap<>();
        while (matcher.find()) {
            actual.put(matcher.group(1), matcher.group(2));
        }
        assertEquals(expected, actual);
        assertEquals(9, Pattern.compile("(?m)^\\(@kling_provider_id,'kling-3\\.0-").matcher(migrationSql).results().count());
    }

    @Test
    void officialPriceGridMatchesProductionBillingResolver() throws Exception {
        JsonNode turbo = rule(migrationSql, "kling_turbo_billing_rule");
        assertBilling(turbo, "720P", null, "KLING30_TURBO_720P", "0.8");
        assertBilling(turbo, "1080P", null, "KLING30_TURBO_1080P", "1.0");

        JsonNode standard = rule(migrationSql, "kling_standard_billing_rule");
        assertBilling(standard, "720P", "off", "KLING30_STANDARD_720P_OFF", "0.6");
        assertBilling(standard, "1080P", "native", "KLING30_STANDARD_1080P_NATIVE", "1.2");
        assertBilling(standard, "4K", "off", "KLING30_STANDARD_4K_OFF", "3.0");
        assertBilling(standard, "4K", "native", "KLING30_STANDARD_4K_NATIVE", "3.0");

        JsonNode omni = rule(migrationSql, "kling_omni_no_reference_video_billing_rule");
        assertBilling(omni, "720P", "off", "KLING30_OMNI_NO_REF_720P_OFF", "0.6");
        assertBilling(omni, "720P", "native", "KLING30_OMNI_NO_REF_720P_NATIVE", "0.8");
        assertBilling(omni, "1080P", "off", "KLING30_OMNI_NO_REF_1080P_OFF", "0.8");
        assertBilling(omni, "1080P", "native", "KLING30_OMNI_NO_REF_1080P_NATIVE", "1.0");
        assertBilling(omni, "4K", "off", "KLING30_OMNI_NO_REF_4K_OFF", "3.0");
        assertBilling(omni, "4K", "native", "KLING30_OMNI_NO_REF_4K_NATIVE", "3.0");

        JsonNode referenceVideo = rule(migrationSql, "kling_omni_reference_video_billing_rule");
        assertBilling(referenceVideo, "720P", "off", "KLING30_OMNI_REF_VIDEO_720P", "0.9");
        assertBilling(referenceVideo, "1080P", "original", "KLING30_OMNI_REF_VIDEO_1080P", "1.2");
        assertBilling(referenceVideo, "4K", "off", "KLING30_OMNI_REF_VIDEO_4K", "3.0");
    }

    @Test
    void omniEditOriginalAudioKeepsReferenceVideoSkuWithoutAudioSplit() throws Exception {
        AiModelConfigVo requestConfig = new AiModelConfigVo();
        requestConfig.setApiSuffix(KlingConstants.PATH_OMNI);
        requestConfig.setCapabilityJson("{\"klingScenario\":\"omni_edit\","
            + "\"supportsAudio\":false,\"defaultAudio\":false}");
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setModelName("kling-3.0-omni-edit");
        request.setPrompt("编辑视频");
        request.setDurationSeconds(5);
        request.setOptions(Map.of(
            "baseVideoUrl", "https://cdn.test/base.mp4",
            "audioMode", "original",
            "resolution", "1080P"));

        ModelCapabilityValidator.normalizeAndValidateVideoAudio(requestConfig, request);
        Map<?, ?> settings = (Map<?, ?>) KlingVideoRequestBuilder.build(requestConfig, request).get("settings");
        assertEquals("original", settings.get("audio"));
        BillingInput billingInput = BillingInputExtractor.fromVideoRequest(request);
        assertEquals(1, billingInput.getParams().get("inputVideoCount"));
        assertEquals("VIDEO_TO_VIDEO", billingInput.getParams().get("generateMode"));

        BillingPriceMultiplierService multiplier = mock(BillingPriceMultiplierService.class);
        when(multiplier.resolveModelMultiplier(any())).thenReturn(BigDecimal.ONE);
        when(multiplier.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        BillingAmountCalculatorImpl calculator = new BillingAmountCalculatorImpl(
            new BillingRuleResolverImpl(MAPPER), multiplier);
        AiModelConfigVo billingModel = new AiModelConfigVo();
        billingModel.setModelCode("kling-3.0-omni-edit");
        billingModel.setModelType("VIDEO");
        billingModel.setBillingMode("SKU");
        billingModel.setBillingMultiplier(BigDecimal.ONE);
        billingModel.setBillingRuleJson(MAPPER.writeValueAsString(
            rule(migrationSql, "kling_omni_reference_video_billing_rule")));

        BillingCalcResult result = calculator.calculatePreHoldAmount(billingModel, billingInput);

        assertTrue(result.isMatched());
        assertEquals("KLING30_OMNI_REF_VIDEO_1080P", result.getSkuCode());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("6.0")));
    }

    @Test
    void migrationRepairsDirtyDefaultsAndPreservesOnlyEnabledPositiveOperatorPricing() throws Exception {
        assertTrue(migrationSql.contains("NULLIF(TRIM(`schedule_strategy_json`),'')"));
        assertTrue(migrationSql.contains("JSON_VALID(`schedule_strategy_json`)=0"));
        assertTrue(migrationSql.contains("THEN VALUES(`schedule_strategy_json`)"));
        assertTrue(migrationSql.contains(
            "COALESCE(JSON_VALID(existing_model.`billing_rule_json`),0)=1"));
        assertTrue(migrationSql.contains("existing_model.`billing_rule_json`,'{\"skus\":[]}'"));
        assertTrue(migrationSql.contains("d4.n * 10000 AS sku_index"));
        assertTrue(migrationSql.contains("CONCAT('$.skus[',sku_indexes.sku_index,'].enabled')"));
        assertTrue(migrationSql.contains("CONCAT('$.skus[',sku_indexes.sku_index,'].price')"));
        assertTrue(migrationSql.contains("CONCAT('$.skus[',sku_indexes.sku_index,'].pricePerSecond')"));
        assertTrue(migrationSql.contains("IN ('INTEGER','DOUBLE')"));
        assertTrue(migrationSql.contains("UPDATE `aid_ai_model` AS existing_model"));
        assertTrue(migrationSql.contains("AND NOT (\n    COALESCE(JSON_VALID(existing_model.`billing_rule_json`),0)=1"));
        assertTrue(migrationSql.contains(
            "WHEN existing_model.`model_code`='kling-3.0-turbo-i2v' THEN @kling_turbo_billing_rule"));
        assertTrue(migrationSql.contains(
            "WHEN existing_model.`model_code` IN ('kling-3.0-omni-feature-video','kling-3.0-omni-edit')"));
        assertFalse(migrationSql.contains("JSON_TABLE"));

        assertFalse(preservesOperatorVideoPricing("not-json"));
        assertFalse(preservesOperatorVideoPricing("{\"skus\":[{}]}"));
        assertFalse(preservesOperatorVideoPricing(
            "{\"skus\":[{\"enabled\":false,\"price\":1},{\"enabled\":false,\"pricePerSecond\":2}]}"));
        assertFalse(preservesOperatorVideoPricing(
            "{\"skus\":[{\"enabled\":true,\"price\":0},{\"enabled\":true,\"pricePerSecond\":-0.1}]}"));
        assertFalse(preservesOperatorVideoPricing(
            "{\"skus\":[{\"enabled\":true,\"price\":0},{\"enabled\":false,\"pricePerSecond\":2}]}"));
        assertTrue(preservesOperatorVideoPricing(
            "{\"skus\":[{\"enabled\":true,\"price\":0.01}]}"));
        assertTrue(preservesOperatorVideoPricing(
            "{\"skus\":[{}, {\"enabled\":true,\"pricePerSecond\":0.0001}]}"));
    }

    @Test
    void initCapabilitiesAndOperatorFacingPricingTextStayConsistent() throws Exception {
        Matcher matcher = Pattern.compile(
            "(?m)^\\(10[0-8],21,'([^']+)'[^\\r\\n]*,'(\\{.*\\})',1\\)[,;]\\r?$")
            .matcher(initSql);
        Map<String, JsonNode> capabilities = new HashMap<>();
        while (matcher.find()) {
            capabilities.put(matcher.group(1), MAPPER.readTree(matcher.group(2)));
        }
        assertEquals(9, capabilities.size());
        assertEquals(9, Pattern.compile(Pattern.quote("\"defaultAudio\":false")).matcher(initSql).results().count());
        assertEquals(9, Pattern.compile(Pattern.quote("\"defaultAudio\":false")).matcher(migrationSql).results().count());
        for (JsonNode capability : capabilities.values()) {
            assertTrue(capability.has("defaultAudio"));
            assertFalse(capability.path("defaultAudio").asBoolean());
        }
        for (String code : Set.of("kling-3.0-omni-feature-video", "kling-3.0-omni-edit")) {
            JsonNode capability = capabilities.get(code);
            assertNotNull(capability, code);
            assertEquals(4, capability.path("maxReferenceImages").asInt());
            assertEquals(4, capability.path("maxElements").asInt());
            assertTrue(capability.path("supportsElements").asBoolean());
            assertEquals(1, capability.path("referenceVideoRules").path("maxVideoCharacterElements").asInt());
        }
        JsonNode editCapability = capabilities.get("kling-3.0-omni-edit");
        assertFalse(editCapability.path("supportsAudio").asBoolean());
        assertEquals("off", editCapability.path("audioModeOptions").get(0).asText());
        assertEquals("original", editCapability.path("audioModeOptions").get(1).asText());
        String pricingText = "官方人民币原价已预置、计费倍率1、默认停用";
        assertEquals(9, Pattern.compile(Pattern.quote(pricingText)).matcher(initSql).results().count());
        assertEquals(9, Pattern.compile(Pattern.quote(pricingText)).matcher(migrationSql).results().count());
        assertFalse(migrationSql.contains("SKU 留空"));
        assertFalse(migrationSql.contains("定价待"));
    }

    @Test
    void sqlCapabilityColumnsMatchKlingScenarioContracts() {
        for (String sql : Set.of(migrationSql, initSql)) {
            assertEquals(0, modelFlags(sql, "kling-3.0-omni-feature-video")[6]);
            assertEquals(0, modelFlags(sql, "kling-3.0-omni-edit")[6]);
            assertEquals(1, modelFlags(sql, "kling-3.0-omni-reference")[10]);
            String editRow = modelRow(sql, "kling-3.0-omni-edit");
            assertTrue(editRow.contains("\"audioModeOptions\":[\"off\",\"original\"]"));
            assertTrue(editRow.contains("\"supportsAudio\":false"));
            assertFalse(editRow.contains("\"supportsAudio\":true"));
        }
    }

    private static void assertSkuIntegrity(JsonNode rule) {
        assertEquals("PER_SECOND", rule.path("meterType").asText());
        JsonNode skus = rule.path("skus");
        assertTrue(skus.isArray());
        assertFalse(skus.isEmpty());
        Set<String> matches = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (JsonNode sku : skus) {
            assertTrue(sku.path("enabled").asBoolean());
            assertTrue(matches.add(sku.path("match").toString()));
            assertTrue(priorities.add(sku.path("priority").asInt()));
            BigDecimal perSecond = sku.path("pricePerSecond").decimalValue();
            assertTrue(perSecond.compareTo(BigDecimal.ZERO) > 0);
            assertEquals(0, sku.path("price").decimalValue().compareTo(perSecond.multiply(BigDecimal.valueOf(5))));
        }
    }

    private static boolean preservesOperatorVideoPricing(String rawRule) {
        try {
            JsonNode skus = MAPPER.readTree(rawRule).path("skus");
            if (!skus.isArray()) {
                return false;
            }
            for (JsonNode sku : skus) {
                if (sku.path("enabled").asBoolean(false)
                    && (isPositiveNumber(sku.get("price")) || isPositiveNumber(sku.get("pricePerSecond")))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isPositiveNumber(JsonNode node) {
        return node != null && node.isNumber() && node.decimalValue().compareTo(BigDecimal.ZERO) > 0;
    }

    private static void assertBilling(JsonNode rule, String resolution, String audioMode,
                                      String skuCode, String pricePerSecond) throws Exception {
        BillingPriceMultiplierService multiplier = mock(BillingPriceMultiplierService.class);
        when(multiplier.resolveModelMultiplier(any())).thenReturn(BigDecimal.ONE);
        when(multiplier.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        BillingAmountCalculatorImpl calculator = new BillingAmountCalculatorImpl(
            new BillingRuleResolverImpl(MAPPER), multiplier);
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("kling-pricing-test");
        model.setModelType("VIDEO");
        model.setBillingMode("SKU");
        model.setBillingMultiplier(BigDecimal.ONE);
        model.setBillingRuleJson(MAPPER.writeValueAsString(rule));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resolution", resolution);
        params.put("duration", 5);
        if (audioMode != null) {
            params.put("audioMode", audioMode);
        }

        BillingCalcResult result = calculator.calculatePreHoldAmount(model, new BillingInput("VIDEO", params));

        assertTrue(result.isMatched());
        assertEquals(skuCode, result.getSkuCode());
        assertEquals(0, result.getSnapshot().getPricePerSecond().compareTo(new BigDecimal(pricePerSecond)));
        assertEquals(0, result.getAmount().compareTo(new BigDecimal(pricePerSecond).multiply(BigDecimal.valueOf(5))));
    }

    private static JsonNode rule(String sql, String variable) throws Exception {
        Matcher matcher = Pattern.compile("(?m)^SET @" + Pattern.quote(variable) + " := '(.*)';\\r?$").matcher(sql);
        assertTrue(matcher.find(), variable);
        return MAPPER.readTree(matcher.group(1).replace("\\\"", "\""));
    }

    private static int[] modelFlags(String sql, String modelCode) {
        String row = modelRow(sql, modelCode);
        Matcher matcher = Pattern.compile(
            "'SKU',@[a-z0-9_]+_billing_rule,1,(?:NULL,)?((?:[01],){10}[01]),'720P'").matcher(row);
        assertTrue(matcher.find(), modelCode);
        String[] raw = matcher.group(1).split(",");
        int[] flags = new int[raw.length];
        for (int index = 0; index < raw.length; index++) {
            flags[index] = Integer.parseInt(raw[index]);
        }
        assertEquals(11, flags.length, modelCode);
        return flags;
    }

    private static String modelRow(String sql, String modelCode) {
        boolean migration = sql.contains("(@kling_provider_id");
        String marker = migration ? "(@kling_provider_id,'" + modelCode + "'" : ",21,'" + modelCode + "'";
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, modelCode);
        int end;
        if (migration) {
            end = sql.indexOf("\n(@kling_provider_id", start + marker.length());
            if (end < 0) {
                end = sql.indexOf("\nON DUPLICATE KEY UPDATE", start + marker.length());
            }
        } else {
            end = sql.indexOf('\n', start + marker.length());
        }
        if (end < 0) {
            end = sql.length();
        }
        return sql.substring(start, end);
    }

    private static Path findRepoFile(String relative) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }
}
