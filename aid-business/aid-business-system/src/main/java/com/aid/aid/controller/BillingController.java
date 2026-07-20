package com.aid.aid.controller;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.dto.BillingRulePreviewRequest;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费规则管理控制器（后台管理端）
 */
@RestController
@RequestMapping("/aid/billing")
@RequiredArgsConstructor
public class BillingController extends BaseController {

    private final BillingAmountCalculator billingAmountCalculator;

    /**
     * 计费规则试算：后台配置SKU规则后，模拟输入参数测试是否能正确命中价格。
     * 对 billingRuleJson 与 testParams 做大小上限保护（限制在 64KB 内），
     * 防止超大 JSON 打垮计算器 / 拖慢计费层。
     */
    @PreAuthorize("@ss.hasPermi('aid:aidmodel:edit')")
    @PostMapping("/preview")
    public AjaxResult billingPreview(@RequestBody BillingRulePreviewRequest request) {
        if (request == null) {
            return AjaxResult.error("请求参数不能为空");
        }
        int ruleLen = request.getBillingRuleJson() == null ? 0 : request.getBillingRuleJson().length();
        if (ruleLen > MAX_BILLING_RULE_CHARS) {
            return AjaxResult.error("规则内容过大");
        }
        int paramsLen = request.getTestParams() == null
                ? 0
                : request.getTestParams().toString().length();
        if (paramsLen > MAX_BILLING_TEST_PARAMS_CHARS) {
            return AjaxResult.error("测试参数过大");
        }
        AiModelConfigVo tempConfig = new AiModelConfigVo();
        tempConfig.setBillingMode("SKU");
        tempConfig.setBillingRuleJson(request.getBillingRuleJson());
        tempConfig.setBillingMultiplier(request.getBillingMultiplier());
        BillingInput input = new BillingInput(null, request.getTestParams());
        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(tempConfig, input);
        return success(result);
    }

    /**
     * 试算接口入参尺寸上限（字符数），防止 UI 侧构造超大 JSON 做 DOS。
     */
    private static final int MAX_BILLING_RULE_CHARS = 64 * 1024;
    private static final int MAX_BILLING_TEST_PARAMS_CHARS = 16 * 1024;
}
