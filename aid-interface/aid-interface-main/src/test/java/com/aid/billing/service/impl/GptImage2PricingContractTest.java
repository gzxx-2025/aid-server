package com.aid.billing.service.impl;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.util.BillingInputExtractor;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GptImage2PricingContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String migrationSql;
    private static String initSql;
    private static JsonNode billingRule;

    @BeforeAll
    static void loadSql() throws Exception {
        migrationSql = Files.readString(findRepoFile("sql/v1.0.0-beta.6.sql"), StandardCharsets.UTF_8);
        initSql = Files.readString(findRepoFile("sql/aid-init.sql"), StandardCharsets.UTF_8);
        billingRule = migrationRule();
    }

    @Test
    void migrationAndInitUseTheSamePerImageRule() throws Exception {
        assertEquals("PER_IMAGE", billingRule.path("meterType").asText());
        assertEquals(3, billingRule.path("skus").size());
        assertEquals(billingRule, initRule());

        String row = initModelRow();
        assertTrue(row.contains(",0.220000,1.0000,"));
        assertTrue(row.contains("',5,'{\\\"maxConcurrency\\\""));
        assertTrue(migrationSql.contains("`billing_multiplier`=1.0000"));
        assertTrue(migrationSql.contains("`cost_credits`=0.220000"));
    }

    @Test
    void twoKAndBelowUseLowPriceWhileFourKUsesHighPrice() throws Exception {
        assertImageBilling("auto", Map.of(), "1K", "GPT_IMAGE_2_UP_TO_2K", "0.1");
        assertImageBilling("1024x1024", Map.of(), "1K", "GPT_IMAGE_2_UP_TO_2K", "0.1");
        assertImageBilling("2048x1152", Map.of(), "2K", "GPT_IMAGE_2_UP_TO_2K", "0.1");
        assertImageBilling("3840x2160", Map.of(), "4K", "GPT_IMAGE_2_4K", "0.22");
        assertImageBilling("2160x3840", Map.of(), "4K", "GPT_IMAGE_2_4K", "0.22");
        assertImageBilling("3840×2160", Map.of(), "4K", "GPT_IMAGE_2_4K", "0.22");
    }

    @Test
    void optionSizeUsesTheSameResolutionAsTheProviderAndUnknownTierFallsBack() throws Exception {
        assertImageBilling("1024x1024", Map.of("size", "3840x2160"),
            "4K", "GPT_IMAGE_2_4K", "0.22");
        assertImageBilling("1024x1024", Map.of("size", "3840x2160", "resolution", "1K"),
            "4K", "GPT_IMAGE_2_4K", "0.22");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("resolution", "3K");
        params.put("expectedImageCount", 1);
        BillingCalcResult result = calculator().calculatePreHoldAmount(model(), new BillingInput("IMAGE", params));

        assertTrue(result.isMatched());
        assertEquals("GPT_IMAGE_2_FALLBACK", result.getSkuCode());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("0.22")));
    }

    private static void assertImageBilling(String size, Map<String, Object> options,
                                           String resolution, String skuCode, String price) throws Exception {
        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setModelName("gpt-image-2");
        request.setSize(size);
        request.setExpectedImageCount(1);
        request.setOptions(options);
        BillingInput input = BillingInputExtractor.fromImageRequest(request, "gpt-image-2", 1);

        assertEquals(resolution, input.getParams().get("resolution"));
        BillingCalcResult result = calculator().calculatePreHoldAmount(model(), input);
        assertTrue(result.isMatched());
        assertEquals(skuCode, result.getSkuCode());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal(price)));
    }

    private static BillingAmountCalculatorImpl calculator() {
        BillingPriceMultiplierService multiplier = mock(BillingPriceMultiplierService.class);
        when(multiplier.resolveModelMultiplier(any())).thenReturn(BigDecimal.ONE);
        when(multiplier.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        return new BillingAmountCalculatorImpl(new BillingRuleResolverImpl(MAPPER), multiplier);
    }

    private static AiModelConfigVo model() throws Exception {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("gpt-image-2");
        model.setModelType("IMAGE");
        model.setBillingMode("SKU");
        model.setBillingMultiplier(BigDecimal.ONE);
        model.setBillingRuleJson(MAPPER.writeValueAsString(billingRule));
        return model;
    }

    private static JsonNode migrationRule() throws Exception {
        Matcher matcher = Pattern.compile("(?m)^SET @gpt_image_2_billing_rule := '(.*)';\\r?$").matcher(migrationSql);
        assertTrue(matcher.find());
        return MAPPER.readTree(matcher.group(1));
    }

    private static JsonNode initRule() throws Exception {
        String row = initModelRow();
        String prefix = ",'SKU','";
        int start = row.indexOf(prefix) + prefix.length();
        int end = row.indexOf("',5,'{\\\"maxConcurrency\\\"", start);
        assertTrue(start >= prefix.length() && end > start);
        return MAPPER.readTree(row.substring(start, end).replace("\\\"", "\""));
    }

    private static String initModelRow() {
        return initSql.lines()
            .filter(line -> line.contains("'gpt-image-2'"))
            .findFirst()
            .orElseThrow();
    }

    private static Path findRepoFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到文件: " + relativePath);
    }
}
