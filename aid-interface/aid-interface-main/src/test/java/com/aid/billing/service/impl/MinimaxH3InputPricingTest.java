package com.aid.billing.service.impl;

import cn.hutool.json.JSONUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinimaxH3InputPricingTest {

    @Test
    void preholdsMaxReferenceVideoAndSettlesFromOfficialUsage() {
        String rule = "{\"mode\":\"SKU\",\"meterType\":\"PER_SECOND\",\"chargeType\":\"VIDEO\","
            + "\"skus\":[{\"skuCode\":\"H3\",\"enabled\":true,\"priority\":1,"
            + "\"match\":{\"resolution\":\"768P\"},\"price\":2.5,\"pricePerSecond\":0.5,"
            + "\"inputPricing\":{\"image\":{\"unitPrice\":0.2,\"freeCount\":5,\"maxCount\":9},"
            + "\"video\":{\"unitPrice\":0.5,\"maxSeconds\":15,\"maxCount\":3}}}],"
            + "\"settleRule\":{\"settleMode\":\"REFUND_ONLY\",\"usageSource\":\"PROVIDER_USAGE\","
            + "\"allowRefund\":true,\"allowExtraCharge\":false}}";
        BillingPriceMultiplierService multipliers = mock(BillingPriceMultiplierService.class);
        when(multipliers.resolveModelMultiplier(any())).thenReturn(BigDecimal.ONE);
        when(multipliers.getGlobalMultiplier()).thenReturn(BigDecimal.ONE);
        BillingAmountCalculatorImpl calculator = new BillingAmountCalculatorImpl(
            new BillingRuleResolverImpl(new ObjectMapper()), multipliers);
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("minimax-h3-reference");
        config.setModelType("video");
        config.setBillingMode("SKU");
        config.setBillingRuleJson(rule);
        config.setBillingMultiplier(BigDecimal.ONE);

        BillingCalcResult prehold = calculator.calculatePreHoldAmount(config, new BillingInput("VIDEO", Map.of(
            "resolution", "768P", "duration", 5, "referenceImageCount", 9,
            "inputVideoCount", 1, "inputVideoSeconds", 0)));
        assertEquals(0, prehold.getAmount().compareTo(new BigDecimal("10.800000")));
        assertEquals(5, prehold.getSnapshot().getInputImageFreeCount());
        assertEquals(15, prehold.getSnapshot().getBilledInputVideoSeconds());

        BillingCalcResult settled = calculator.calculateSettleAmount(prehold.getAmount(),
            JSONUtil.toJsonStr(prehold.getSnapshot()), Map.of(
                "actualDuration", 4, "actualInputImageCount", 6, "actualInputVideoSeconds", 3));
        assertEquals(0, settled.getAmount().compareTo(new BigDecimal("3.700000")));
        assertEquals(6, settled.getSnapshot().getActualInputImageCount());
        assertEquals(3, settled.getSnapshot().getActualInputVideoSeconds());
    }
}
