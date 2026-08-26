package com.aid.billing.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.billing.dto.BillingQuoteRequest;
import com.aid.billing.service.BillingQuoteService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** C 端权威计费报价接口。 */
@RestController
@RequestMapping("/api/user/billing")
@RequiredArgsConstructor
public class BillingQuoteController extends BaseController
{
    private final BillingQuoteService billingQuoteService;

    @PostMapping("/quote")
    public AjaxResult quote(@Valid @RequestBody BillingQuoteRequest request)
    {
        return success(billingQuoteService.quote(request, SecurityUtils.getUserId()));
    }
}
