package com.aid.common.aid.alipay.controller;

import com.aid.common.aid.alipay.core.AlipayTemplateFactory;
import com.aid.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付宝配置Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/alipay/config")
public class AlipayConfigController {

    @Autowired
    private AlipayTemplateFactory alipayTemplateFactory;

    /**
     * 刷新支付宝配置
     * 在配置页面修改后点击"刷新配置"按钮调用
     */
    @PreAuthorize("@ss.hasPermi('alipay:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        alipayTemplateFactory.refresh();
        return AjaxResult.success();
    }

    /**
     * 获取当前生效的支付宝配置
     * 在配置页面展示当前使用的参数
     */
    @PreAuthorize("@ss.hasPermi('alipay:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig() {
        Map<String, String> config = alipayTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

}
