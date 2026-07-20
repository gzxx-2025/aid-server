package com.aid.billing.estimate;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;
import org.springframework.stereotype.Component;

/**
 * SKU_PACKAGE 计费口径的预冻结参数估算策略。
 * SKU_PACKAGE 模型（豆包视频等）的预冻结直接使用匹配 SKU 的整包价，
 * 不依赖额外的参数估算。
 * 本策略为 pass-through，保持现有行为不变。
 */
@Component
public class SkuPackageBillingEstimateStrategy implements BillingEstimateStrategy {

    @Override
    public void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig) {
        // SKU_PACKAGE 模型无需额外估算参数，预扣公式直接使用 matchedSku.price
    }
}
