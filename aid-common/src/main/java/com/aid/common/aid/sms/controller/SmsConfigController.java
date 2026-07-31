package com.aid.common.aid.sms.controller;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.sms.core.SmsTemplateFactory;
import com.aid.common.aid.sms.dto.SmsTestRequest;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * 短信配置Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/sms/config")
public class SmsConfigController {

    @Autowired
    private SmsTemplateFactory smsTemplateFactory;

    /**
     * 刷新短信配置
     * 在配置页面修改后点击"刷新配置"按钮调用
     */
    @PreAuthorize("@ss.hasPermi('sms:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        smsTemplateFactory.refresh();
        return AjaxResult.success();
    }

    /**
     * 获取当前生效的短信配置
     * 在配置页面展示当前使用的参数
     */
    @PreAuthorize("@ss.hasPermi('sms:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig() {
        Map<String, String> config = smsTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

    /**
     * 测试发送短信
     * 用于管理端在配置页面验证短信服务商参数是否正确。
     * 使用 aid_config 中配置的默认模板和验证码参数名，发送一条固定测试验证码。
     */
    @PreAuthorize("@ss.hasPermi('sms:config:refresh')")
    @PostMapping("/testSend")
    public AjaxResult testSend(@RequestBody SmsTestRequest request) {
        // 手机号必填校验
        if (Objects.isNull(request) || StrUtil.isBlank(request.getPhone())) {
            return AjaxResult.error("手机号必填");
        }
        // 未传则默认用 1234 作为测试验证码，避免触发真实业务
        String code = StrUtil.isBlank(request.getCode()) ? "1234" : request.getCode().trim();
        try {
            // 走统一的默认模板通道，校验配置是否打通
            SmsResult result = smsTemplateFactory.sendCode(request.getPhone().trim(), code);
            if (result != null && result.isSuccess()) {
                // 成功原样返回 SDK 响应，便于排查
                return AjaxResult.success("发送成功", result);
            }
            log.info("短信测试发送失败: phone={}, result={}", request.getPhone(), result);
            return AjaxResult.error(result != null ? result.getMessage() : "发送失败", result);
        } catch (Exception e) {
            log.error("短信测试发送异常: phone={}, msg={}", request.getPhone(), e.getMessage(), e);
            return AjaxResult.error("发送异常");
        }
    }

}
