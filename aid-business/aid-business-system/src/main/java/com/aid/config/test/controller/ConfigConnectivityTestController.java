package com.aid.config.test.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.config.test.ConfigConnectivityTestRegistry;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.config.test.TestPayloadSecretResolver;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 配置连通性测试统一入口（后台）。
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/system/config")
@RequiredArgsConstructor
public class ConfigConnectivityTestController extends BaseController {

    /**
     * 连通性测试注册中心（按 testKey 路由到对应 Tester）。
     */
    private final ConfigConnectivityTestRegistry registry;

    /**
     * 密钥脱敏回写解析器：页面回传脱敏密钥时还原为数据库真实值，避免拿脱敏串鉴权导致测试必失败。
     */
    private final TestPayloadSecretResolver secretResolver;

    /**
     * 执行配置连通性测试。
     *
     * 入参中的密钥仅在内存流转，不落库、不写日志；调试明细 details 仅超管可见，非超管一律置空。
     *
     * @param request 测试请求（含 testKey 与临时配置 payload）
     * @return 测试结果，统一放在 data 字段返回
     */
    @PreAuthorize("@ss.hasPermi('system:config:test')")
    @PostMapping("/test")
    public AjaxResult test(@RequestBody @Valid ConfigTestRequest request) {
        // 还原页面回传的脱敏密钥为数据库真实值（仅当未修改/为空时），再路由到对应 Tester
        secretResolver.restoreMaskedSecrets(request.getTestKey(), request.getPayload());
        // 路由到对应 Tester 执行探活，注册中心已对异常做兜底
        ConfigTestResult result = registry.run(request);
        // 非超管抹掉调试明细，避免堆栈/内部信息外泄
        if (!SecurityUtils.isAdmin(SecurityUtils.getUserId())) {
            result.setDetails(null);
        }
        return AjaxResult.success(result);
    }
}
