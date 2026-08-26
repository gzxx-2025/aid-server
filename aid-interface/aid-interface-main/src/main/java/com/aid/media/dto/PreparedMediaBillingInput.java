package com.aid.media.dto;

import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

/** 通过正式媒体前置校验与归一化后得到的只读计费输入。 */
public record PreparedMediaBillingInput(AiModelConfigVo modelConfig, BillingInput billingInput)
{
}
