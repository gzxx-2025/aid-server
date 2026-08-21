package com.aid.billing.service.impl;

import cn.hutool.json.JSONUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.estimate.TokenBillingEstimateStrategy;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeedanceTokenBillingTest {

    @Test
    void preholdsInputMaximumThenSettlesExactCompletionTokens() {
        String rule = "{\"mode\":\"SKU\",\"meterType\":\"TOKEN\",\"chargeType\":\"VIDEO\","
                + "\"videoTokenEstimate\":{\"strategy\":\"PIXEL_FPS\",\"framesPerSecond\":24,"
                + "\"tokenDivisor\":1024,\"autoDurationMaxSeconds\":30,\"inputVideoMaxSeconds\":30,"
                + "\"fallbackResolution\":\"720P\",\"minimumInputSecondsNumerator\":2,"
                + "\"minimumInputSecondsDenominator\":3,\"dimensions\":{"
                + "\"480P\":{\"16:9\":[854,480],\"default\":[992,432]},"
                + "\"720P\":{\"default\":[1112,834]}}},\"skus\":["
                + "{\"skuCode\":\"IN\",\"skuName\":\"含输入视频\",\"enabled\":true,\"priority\":1,"
                + "\"match\":{\"resolution\":\"480P\",\"inputVideoCountMin\":1},"
                + "\"inputPricePerMillion\":0,\"outputPricePerMillion\":42},"
                + "{\"skuCode\":\"OUT\",\"skuName\":\"无输入视频\",\"enabled\":true,\"priority\":2,"
                + "\"match\":{\"resolution\":\"480P\"},\"inputPricePerMillion\":0,"
                + "\"outputPricePerMillion\":70}]}";
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("doubao-seedance-2.5-reference");
        config.setModelName("Seedance 2.5");
        config.setModelType("video");
        config.setBillingMode("SKU");
        config.setBillingRuleJson(rule);
        config.setBillingMultiplier(BigDecimal.ONE);
        Map<String, Object> params = new HashMap<>();
        params.put("resolution", "480P");
        params.put("aspectRatio", "16:9");
        params.put("duration", 5);
        params.put("autoDuration", false);
        params.put("inputVideoCount", 1);
        BillingInput input = new BillingInput("VIDEO", params);
        new TokenBillingEstimateStrategy().enrichEstimate(input, config);

        BillingPriceMultiplierService multipliers = mock(BillingPriceMultiplierService.class);
        when(multipliers.resolveModelMultiplier(any())).thenReturn(BigDecimal.ONE);
        when(multipliers.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        BillingAmountCalculatorImpl calculator = new BillingAmountCalculatorImpl(
                new BillingRuleResolverImpl(new ObjectMapper()), multipliers);
        BillingCalcResult prehold = calculator.calculatePreHoldAmount(config, input);
        assertEquals("IN", prehold.getSkuCode());
        assertEquals(0, prehold.getAmount().compareTo(new BigDecimal("14.123046")));

        BillingCalcResult settled = calculator.calculateSettleAmount(prehold.getAmount(),
                JSONUtil.toJsonStr(prehold.getSnapshot()), Map.of("completion_tokens", 86468));
        assertEquals(0, settled.getAmount().compareTo(new BigDecimal("3.631656")));
        assertEquals(86468, settled.getSnapshot().getActualOutputTokens());
    }
}
