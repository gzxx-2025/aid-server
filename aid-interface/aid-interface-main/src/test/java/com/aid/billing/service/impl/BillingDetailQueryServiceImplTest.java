package com.aid.billing.service.impl;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.vo.BillingRuleItemVO;
import com.aid.billing.vo.ModelBillingDetailVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BillingDetailQueryServiceImplTest
{
    private static final String SEEDANCE_RULE = """
            {
              "mode":"SKU",
              "meterType":"PER_SECOND",
              "skus":[
                {
                  "skuCode":"SEEDANCE20_720P_INVIDEO",
                  "skuName":"Seedance2.0 720P含输入视频",
                  "enabled":true,
                  "priority":2,
                  "match":{"resolution":"720P","inputVideoCountMin":1},
                  "remark":"内部含视频原价备注",
                  "pricePerSecond":0.6048,
                  "inputPricing":{"video":{"unitPrice":0.6048,"maxSeconds":15,"maxCount":3}}
                },
                {
                  "skuCode":"SEEDANCE20_720P",
                  "skuName":"Seedance2.0 720P",
                  "enabled":true,
                  "priority":12,
                  "match":{"resolution":"720P"},
                  "remark":"内部普通原价备注",
                  "pricePerSecond":0.9936
                },
                {
                  "skuCode":"DISABLED_INPUT_VIDEO",
                  "skuName":"已停用视频档",
                  "enabled":false,
                  "priority":1,
                  "match":{"resolution":"4K","inputVideoCountMin":1},
                  "pricePerSecond":99,
                  "inputPricing":{"image":{"unitPrice":99},"video":{"unitPrice":99}}
                }
              ]
            }
            """;

    @Test
    void shouldReturnFinalSeedancePricesAndHideInternalFields() throws Exception
    {
        BillingDetailQueryServiceImpl service = new BillingDetailQueryServiceImpl(
                mock(IAidAiModelService.class),
                mock(IAidAiProviderService.class),
                mock(BillingPriceMultiplierService.class));
        AidAiModel model = new AidAiModel();
        model.setId(14L);
        model.setModelCode("doubao-seedance-2.0");
        model.setModelName("豆包Seedance 2.0");
        model.setModelType("video");
        model.setBillingMode("SKU");
        model.setBillingMultiplier(new BigDecimal("1.1"));
        model.setBillingRuleJson(SEEDANCE_RULE);
        model.setIsFree(Boolean.TRUE);
        model.setRemark("内部模型备注");

        ModelBillingDetailVO detail = service.buildModelBillingDetail(
                model, "火山方舟", null, BigDecimal.ONE);

        BillingRuleItemVO normal = findRule(detail, "SEEDANCE20_720P");
        assertEquals(new BigDecimal("1.09296"), normal.getPricePerSecond());
        assertEquals(new BigDecimal("10.9296"), normal.getPricePerSecond()
                .multiply(BigDecimal.TEN).setScale(4, RoundingMode.HALF_UP));
        BillingRuleItemVO withVideo = findRule(detail, "SEEDANCE20_720P_INVIDEO");
        assertEquals(new BigDecimal("0.66528"), withVideo.getPricePerSecond());
        assertEquals(new BigDecimal("0.66528"), withVideo.getInputVideoPricePerSecond());
        assertEquals(1, withVideo.getInputVideoCountMin());
        assertEquals(Boolean.TRUE, detail.getInputPricing().getVideoSupported());
        assertTrue(detail.getIsFree());
        assertEquals(15, detail.getInputPricing().getVideoMaxSeconds());
        assertEquals(3, detail.getInputPricing().getVideoMaxCount());

        String json = new ObjectMapper().writeValueAsString(detail);
        assertFalse(json.contains("priceMultiplier"));
        assertFalse(json.contains("remark"));
        assertFalse(detail.getColumns().stream().anyMatch(column -> "remark".equals(column.getKey())));
        assertFalse(detail.getRules().stream().anyMatch(rule -> "DISABLED_INPUT_VIDEO".equals(rule.getSkuCode())));
        assertFalse(detail.getColumns().stream().anyMatch(column -> "inputImagePrice".equals(column.getKey())));

        ModelBillingDetailVO twoLayerMultiplierDetail = service.buildModelBillingDetail(
                model, "火山方舟", null, new BigDecimal("2"));
        assertEquals(new BigDecimal("2.18592"),
                findRule(twoLayerMultiplierDetail, "SEEDANCE20_720P").getPricePerSecond());
        String twoLayerJson = new ObjectMapper().writeValueAsString(twoLayerMultiplierDetail);
        assertFalse(twoLayerJson.contains("priceMultiplier"));
    }

    private BillingRuleItemVO findRule(ModelBillingDetailVO detail, String skuCode)
    {
        BillingRuleItemVO item = detail.getRules().stream()
                .filter(rule -> skuCode.equals(rule.getSkuCode()))
                .findFirst()
                .orElse(null);
        assertNotNull(item);
        return item;
    }
}
