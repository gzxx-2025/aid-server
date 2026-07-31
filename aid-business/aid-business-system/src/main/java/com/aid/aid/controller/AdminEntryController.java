package com.aid.aid.controller;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;

/**
 * 后台登录入口安全控制：可配置的随机登录路径。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/adminEntry")
public class AdminEntryController extends BaseController {

    private static final String CATEGORY = "admin_entry";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CODE = "access_code";

    @Autowired
    private IAidConfigService aidConfigService;

    @Autowired
    private com.aid.aid.security.IpRateLimitGuard ipRateLimitGuard;

    /**
     * 入口状态（匿名）：仅返回是否启用随机入口，不返回访问码。
     */
    @Anonymous
    @GetMapping("/status")
    public AjaxResult status() {
        AjaxResult r = AjaxResult.success();
        r.put("enabled", readEnabled());
        return r;
    }

    /**
     * 校验访问码（匿名）：仅返回是否匹配，不回显正确码。
     * 未启用时直接放行（valid=true），由前端按未启用走默认入口。
     */
    @Anonymous
    @PostMapping("/verify")
    public AjaxResult verify(@RequestBody(required = false) VerifyRequest body) {
        AjaxResult r = AjaxResult.success();
        // IP 限流：单 IP 每分钟尝试次数由 aid_config(admin_entry.rate_limit_per_min) 动态控制，默认 10
        if (!ipRateLimitGuard.allowByConfig("admin_entry_verify", "admin_entry", "rate_limit_per_min", 10)) {
            r.put("valid", false);
            return r;
        }
        if (!readEnabled()) {
            r.put("valid", true);
            return r;
        }
        String input = Objects.isNull(body) ? null : body.getCode();
        String real = readCode();
        boolean valid = StrUtil.isNotBlank(real) && real.equals(StrUtil.trimToEmpty(input));
        r.put("valid", valid);
        return r;
    }

    /** 读取启用开关，缺失/异常一律按未启用（fail-safe，避免误锁死入口） */
    private boolean readEnabled() {
        try {
            String v = aidConfigService.getConfigValue(CATEGORY, KEY_ENABLED);
            return "true".equalsIgnoreCase(v) || "Y".equalsIgnoreCase(v) || "1".equals(v);
        } catch (Exception e) {
            log.warn("读取后台入口开关异常，按未启用处理: {}", e.getMessage());
            return false;
        }
    }

    /** 读取访问码 */
    private String readCode() {
        try {
            return aidConfigService.getConfigValue(CATEGORY, KEY_CODE);
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验请求体 */
    public static class VerifyRequest {
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}
