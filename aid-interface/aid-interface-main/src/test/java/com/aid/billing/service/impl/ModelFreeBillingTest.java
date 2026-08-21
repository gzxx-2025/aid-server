package com.aid.billing.service.impl;

import cn.hutool.json.JSONUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelFreeBillingTest
{
    @Test
    void freeModelKeepsSkuAndPriceSnapshotButReturnsZero()
    {
        BillingAmountCalculatorImpl calculator = calculator();
        AiModelConfigVo model = tokenModel(true);

        BillingCalcResult result = calculator.calculatePreHoldAmount(
                model, new BillingInput("TEXT", Map.of("inputTokens", 1000, "outputTokens", 500)));

        assertTrue(result.isMatched());
        assertEquals("TOKEN_PRICE", result.getSkuCode());
        assertEquals(0, result.getAmount().compareTo(BigDecimal.ZERO));
        assertEquals(Boolean.TRUE, result.getSnapshot().getIsFree());
        assertEquals(new BigDecimal("2"), result.getSnapshot().getInputPricePerMillion());
        assertEquals(new BigDecimal("4"), result.getSnapshot().getOutputPricePerMillion());
        assertEquals(new BigDecimal("100"), result.getSnapshot().getFinalBillingMultiplier());
    }

    @Test
    void snapshotDecisionSurvivesModelSwitchesAndHistoricalSnapshotRemainsCharged()
    {
        BillingAmountCalculatorImpl calculator = calculator();
        AiModelConfigVo freeModel = tokenModel(true);
        BillingCalcResult frozenFree = calculator.calculatePreHoldAmount(
                freeModel, new BillingInput("TEXT", Map.of("inputTokens", 1000, "outputTokens", 500)));
        freeModel.setIsFree(false);

        BillingCalcResult freeSettlement = calculator.calculateSettleAmount(
                BigDecimal.ZERO, JSONUtil.toJsonStr(frozenFree.getSnapshot()),
                Map.of("input_tokens", 2000, "output_tokens", 1000));
        assertEquals(0, freeSettlement.getAmount().compareTo(BigDecimal.ZERO));

        AiModelConfigVo chargedModel = tokenModel(false);
        BillingCalcResult frozenCharged = calculator.calculatePreHoldAmount(
                chargedModel, new BillingInput("TEXT", Map.of("inputTokens", 1000, "outputTokens", 500)));
        chargedModel.setIsFree(true);
        BillingCalcResult chargedSettlement = calculator.calculateSettleAmount(
                frozenCharged.getAmount(), JSONUtil.toJsonStr(frozenCharged.getSnapshot()),
                Map.of("input_tokens", 1000, "output_tokens", 500));
        assertEquals(0, chargedSettlement.getAmount().compareTo(new BigDecimal("0.400000")));

        BillingSnapshot historical = frozenFree.getSnapshot();
        historical.setIsFree(null);
        historical.setPreHoldAmount(new BigDecimal("0.4"));
        BillingCalcResult historicalSettlement = calculator.calculateSettleAmount(
                new BigDecimal("0.4"), JSONUtil.toJsonStr(historical),
                Map.of("input_tokens", 1000, "output_tokens", 500));
        assertEquals(0, historicalSettlement.getAmount().compareTo(new BigDecimal("0.400000")));
        assertFalse(Boolean.TRUE.equals(historicalSettlement.getSnapshot().getIsFree()));
    }

    @Test
    void chargedModelAmountIsUnchanged()
    {
        BillingCalcResult result = calculator().calculatePreHoldAmount(
                tokenModel(false), new BillingInput("TEXT", Map.of("inputTokens", 1000, "outputTokens", 500)));

        assertEquals(0, result.getAmount().compareTo(new BigDecimal("0.400000")));
        assertEquals(Boolean.FALSE, result.getSnapshot().getIsFree());
    }

    @Test
    void freeFixedImageKeepsOriginalUnitPriceAndReturnsZero()
    {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setModelCode("fixed-image");
        model.setModelName("Fixed Image");
        model.setModelType("image");
        model.setBillingMode("FIXED");
        model.setCostCredits(new BigDecimal("1.5"));
        model.setBillingMultiplier(new BigDecimal("1.0"));
        model.setIsFree(true);

        BillingCalcResult result = calculator().calculatePreHoldAmount(
                model, new BillingInput("IMAGE", Map.of("expectedImageCount", 2)));

        assertEquals(0, result.getAmount().compareTo(BigDecimal.ZERO));
        assertEquals(Boolean.TRUE, result.getSnapshot().getIsFree());
        assertEquals(0, result.getSnapshot().getUnitPrice().compareTo(new BigDecimal("1.5")));
        assertEquals(0, result.getSnapshot().getBaseAmount().compareTo(new BigDecimal("3.0")));
    }

    private BillingAmountCalculatorImpl calculator()
    {
        BillingPriceMultiplierService multiplier = mock(BillingPriceMultiplierService.class);
        when(multiplier.resolveModelMultiplier(new BigDecimal("1.0"))).thenReturn(BigDecimal.ONE);
        when(multiplier.getGlobalMultiplier()).thenReturn(new BigDecimal("100"));
        return new BillingAmountCalculatorImpl(
                new BillingRuleResolverImpl(new ObjectMapper()), multiplier);
    }

    private AiModelConfigVo tokenModel(boolean free)
    {
        AiModelConfigVo model = new AiModelConfigVo();
        model.setId(7L);
        model.setModelCode("token-model");
        model.setModelName("Token Model");
        model.setModelType("text");
        model.setBillingMode("SKU");
        model.setBillingMultiplier(new BigDecimal("1.0"));
        model.setBillingVersion(3);
        model.setIsFree(free);
        model.setBillingRuleJson("{\"mode\":\"SKU\",\"meterType\":\"TOKEN\",\"skus\":[{"
                + "\"skuCode\":\"TOKEN_PRICE\",\"skuName\":\"标准档\",\"enabled\":true,\"priority\":1,"
                + "\"match\":{},\"inputPricePerMillion\":2,\"outputPricePerMillion\":4}]}");
        return model;
    }
}
