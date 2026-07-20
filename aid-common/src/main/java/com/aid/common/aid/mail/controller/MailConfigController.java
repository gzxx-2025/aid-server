package com.aid.common.aid.mail.controller;

import com.aid.common.aid.mail.core.MailTemplateFactory;
import com.aid.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 邮箱配置Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/mail/config")
public class MailConfigController {

    @Autowired
    private MailTemplateFactory mailTemplateFactory;

    /**
     * 刷新邮箱配置
     * 在配置页面修改后点击"刷新配置"按钮调用
     */
    @PreAuthorize("@ss.hasPermi('mail:config:refresh')")
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        mailTemplateFactory.refresh();
        return AjaxResult.success();
    }

    /**
     * 获取当前生效的邮箱配置
     * 在配置页面展示当前使用的参数
     */
    @PreAuthorize("@ss.hasPermi('mail:config:query')")
    @GetMapping("/current")
    public AjaxResult getCurrentConfig() {
        Map<String, String> config = mailTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

}
