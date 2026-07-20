package com.aid.billing.estimate;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;
import org.springframework.stereotype.Component;

/**
 * PER_SECOND 计费口径的预冻结参数估算策略。
 * PER_SECOND 模型（万象视频等）的预冻结参数已在 BillingInputExtractor.fromVideoRequest() 中
 * 完整提取（duration、resolution 等），无需额外估算。
 * 本策略为 pass-through，保持现有行为不变。
 */
@Component
public class PerSecondBillingEstimateStrategy implements BillingEstimateStrategy {

    @Override
    public void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig) {
        // PER_SECOND 模型无需额外估算参数，预扣公式仅依赖 duration × pricePerSecond
    }
}
