package com.aid.billing.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.billing.dto.BillingDetailRequest;
import com.aid.billing.service.IBillingDetailQueryService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;

/**
 * 计费详情 Controller（C 端公共接口，免登录），按模型大类返回实时计费规则与折算后价格。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/public/billing")
public class BillingDetailController extends BaseController
{
    @Resource
    private IBillingDetailQueryService billingDetailQueryService;

    /**
     * 查询计费详情（公共接口，免登录）。
     * 按 LLM（文本）/ 图片 / 视频 / 配音 分组返回在运行模型的计费规则、档位与价格，
     * 价格已折算为用户实际支付的最终价（单位 Credits），支持按模型大类与名称筛选。
     *
     * @param request 计费详情查询请求（modelType / modelName 均可选）
     * @return 分组计费详情
     */
    @Anonymous
    @PostMapping("/detail")
    public AjaxResult detail(@RequestBody BillingDetailRequest request)
    {
        return success(billingDetailQueryService.queryBillingDetail(request));
    }
}
