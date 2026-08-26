package com.aid.billing.service;

import com.aid.billing.dto.BillingQuoteRequest;
import com.aid.billing.vo.BillingQuoteVO;

/** 用户侧只读报价服务。 */
public interface BillingQuoteService
{
    BillingQuoteVO quote(BillingQuoteRequest request, Long userId);
}
