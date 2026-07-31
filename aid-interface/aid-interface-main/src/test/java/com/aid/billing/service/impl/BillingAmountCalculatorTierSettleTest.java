package com.aid.billing.service.impl;

import cn.hutool.json.JSONUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class BillingAmountCalculatorTierSettleTest
{
    @Test
    void shouldRematchFrozenTokenRuleWithActualSingleCallUsage()
    {
        String ruleJson = "{\"mode\":\"SKU\",\"meterType\":\"TOKEN\",\"chargeType\":\"TEXT\","
                + "\"matchStrategy\":\"FIRST_HIT\",\"skus\":["
                + "{\"skuCode\":\"LOW\",\"skuName\":\"低档\",\"enabled\":true,\"priority\":1,"
                + "\"match\":{\"inputTokensMin\":0,\"inputTokensMax\":100},"
                + "\"inputPricePerMillion\":1,\"outputPricePerMillion\":2},"
                + "{\"skuCode\":\"HIGH\",\"skuName\":\"高档\",\"enabled\":true,\"priority\":2,"
                + "\"match\":{\"inputTokensMin\":101,\"inputTokensMax\":1000},"
                + "\"inputPricePerMillion\":10,\"outputPricePerMillion\":20}]}";
        BillingRuleResolverImpl resolver = new BillingRuleResolverImpl(new ObjectMapper());
        BillingAmountCalculatorImpl calculator = new BillingAmountCalculatorImpl(
                resolver, mock(BillingPriceMultiplierService.class));

        BillingSnapshot frozen = new BillingSnapshot();
        frozen.setModelName("tiered-model");
        frozen.setBillingMode("SKU");
        frozen.setMeterType("TOKEN");
        frozen.setBillingRuleJson(ruleJson);
        frozen.setSkuCode("LOW");
        frozen.setSkuName("低档");
        frozen.setInputPricePerMillion(BigDecimal.ONE);
        frozen.setOutputPricePerMillion(new BigDecimal("2"));
        frozen.setFinalBillingMultiplier(new BigDecimal("100"));
        Map<String, Object> requestParams = new LinkedHashMap<>();
        requestParams.put("inputTokens", 50);
        requestParams.put("outputTokens", 20);
        frozen.setRequestParams(requestParams);

        BillingCalcResult result = calculator.calculateSettleAmount(
                new BigDecimal("0.01"), JSONUtil.toJsonStr(frozen),
                Map.of("input_tokens", 200, "output_tokens", 50));

        assertEquals("HIGH", result.getSkuCode());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("0.300000")));
        assertEquals(new BigDecimal("10"), result.getSnapshot().getInputPricePerMillion());
        assertEquals(new BigDecimal("20"), result.getSnapshot().getOutputPricePerMillion());
    }
}
