package com.aid.billing.estimate;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;
import org.springframework.stereotype.Component;

/**
 * PER_IMAGE 计费口径的预冻结参数估算策略。
 * PER_IMAGE 模型（即梦、阿里万相等）的预冻结参数已在 BillingInputExtractor.fromImageRequest() 中
 * 完整提取（expectedImageCount、generateMode 等），无需额外估算。
 * 本策略为 pass-through，保持现有行为不变。
 */
@Component
public class PerImageBillingEstimateStrategy implements BillingEstimateStrategy {

    @Override
    public void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig) {
        // PER_IMAGE 模型无需额外估算参数，预扣公式仅依赖 expectedImageCount × unitPrice
    }
}
