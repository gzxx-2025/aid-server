package com.aid.media.service;

import com.aid.billing.vo.BillingQuoteVO;
import com.aid.media.dto.MediaImageGenerateRequest;

/** 将已由业务正式计划组装的媒体请求转换为无副作用权威报价。 */
public interface MediaBillingQuoteService
{
    BillingQuoteVO quoteImage(String quoteType, MediaImageGenerateRequest request, int quantity);

    /** 对尚待前序步骤补齐媒体输入的请求报价，并显式标为预估。 */
    BillingQuoteVO quotePlannedImage(String quoteType, MediaImageGenerateRequest request, int quantity);
}
