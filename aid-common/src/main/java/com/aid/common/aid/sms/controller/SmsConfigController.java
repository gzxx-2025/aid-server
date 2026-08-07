package com.aid.common.aid.sms.controller;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.sms.core.SmsTemplateFactory;
import com.aid.common.aid.sms.dto.SmsTestRequest;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.log.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@RequiredArgsConstructor
public class SmsConfigController {

    private final SmsTemplateFactory smsTemplateFactory;

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
     * 云短信使用配置的模板 ID；短信宝使用本平台维护的完整内容模板。
     */
    @PreAuthorize("@ss.hasPermi('sms:config:refresh')")
    @PostMapping("/testSend")
    public AjaxResult testSend(@RequestBody SmsTestRequest request) {
        // 手机号必填校验
        if (Objects.isNull(request) || StrUtil.isBlank(request.getPhone())) {
            return AjaxResult.error("手机号必填");
        }
        // 每次生成随机验证码，避免同手机号重复提交固定内容被运营商拦截。
        String code = StrUtil.isBlank(request.getCode()) ? RandomUtil.randomNumbers(6) : request.getCode().trim();
        try {
            // 走统一的默认模板通道，校验配置是否打通
            SmsResult result = smsTemplateFactory.sendCode(request.getPhone().trim(), code);
            if (!Objects.isNull(result) && result.isSuccess()) {
                // 服务商成功响应仅代表请求已受理，原样返回通道说明便于判断送达状态。
                return AjaxResult.success(StrUtil.blankToDefault(result.getMessage(), "发送已受理"), result);
            }
            log.info("短信测试发送失败: phone={}, result={}",
                    LogSanitizer.maskPhone(request.getPhone()), result);
            return AjaxResult.error(!Objects.isNull(result) ? result.getMessage() : "发送失败", result);
        } catch (Exception e) {
            log.error("短信测试发送异常: phone={}, exception={}",
                    LogSanitizer.maskPhone(request.getPhone()), e.getClass().getSimpleName());
            return AjaxResult.error("发送异常");
        }
    }

}
