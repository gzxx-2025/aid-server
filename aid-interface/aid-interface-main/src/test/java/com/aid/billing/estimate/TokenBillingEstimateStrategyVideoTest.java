package com.aid.billing.estimate;

import com.aid.billing.dto.BillingInput;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenBillingEstimateStrategyVideoTest {

    @Test
    void matchesOfficialSeedanceMinimumTokenBoundaries() {
        // Seedance 2.5：输出5秒时最低有效输入4秒；输入2/4秒都为86468，输入5秒为96075。
        assertEquals(86468, TokenBillingEstimateStrategy.estimatePixelVideoTokens(854, 480, 24, 1024, 5, 2, 2, 3));
        assertEquals(86468, TokenBillingEstimateStrategy.estimatePixelVideoTokens(854, 480, 24, 1024, 5, 4, 2, 3));
        assertEquals(96075, TokenBillingEstimateStrategy.estimatePixelVideoTokens(854, 480, 24, 1024, 5, 5, 2, 3));

        // Seedance 2.0 使用其官方480P尺寸，最低规则相同。
        assertEquals(90396, TokenBillingEstimateStrategy.estimatePixelVideoTokens(864, 496, 24, 1024, 5, 2, 2, 3));
        assertEquals(90396, TokenBillingEstimateStrategy.estimatePixelVideoTokens(864, 496, 24, 1024, 5, 4, 2, 3));
        assertEquals(100440, TokenBillingEstimateStrategy.estimatePixelVideoTokens(864, 496, 24, 1024, 5, 5, 2, 3));
    }

    @Test
    void autoDurationAndUnknownResolutionUseConfiguredSafeMaximum() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("configured-video-token-model");
        config.setBillingRuleJson("{\"meterType\":\"TOKEN\",\"videoTokenEstimate\":{"
                + "\"strategy\":\"PIXEL_FPS\",\"framesPerSecond\":24,\"tokenDivisor\":1024,"
                + "\"autoDurationMaxSeconds\":30,\"inputVideoMaxSeconds\":30,"
                + "\"fallbackResolution\":\"720P\",\"minimumInputSecondsNumerator\":2,"
                + "\"minimumInputSecondsDenominator\":3,\"dimensions\":{"
                + "\"480P\":{\"default\":[854,480]},\"720P\":{\"default\":[1280,720]}}}}");
        Map<String, Object> params = new HashMap<>();
        params.put("resolution", "unknown");
        params.put("duration", 5);
        params.put("autoDuration", true);
        params.put("inputVideoCount", 1);
        BillingInput input = new BillingInput("VIDEO", params);

        new TokenBillingEstimateStrategy().enrichEstimate(input, config);

        assertEquals("720P", params.get("resolution"));
        assertEquals(1_296_000, params.get("outputTokens")); // (30输出+30输入)*1280*720*24/1024
    }

    @Test
    void rejectsVideoTokenBillingWithoutExplicitEstimatorInsteadOfZeroPrehold() {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode("broken-video-token-model");
        config.setBillingRuleJson("{\"meterType\":\"TOKEN\",\"chargeType\":\"VIDEO\",\"skus\":[]}");
        BillingInput input = new BillingInput("VIDEO", new HashMap<>());

        assertThrows(ServiceException.class,
                () -> new TokenBillingEstimateStrategy().enrichEstimate(input, config));
    }
}
