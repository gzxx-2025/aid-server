package com.aid.common.aid.wxlogin.controller;

import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.core.domain.AjaxResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信公众号登录配置控制器
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/wxLogin/config")
@RequiredArgsConstructor
public class WxLoginConfigController {

    private final WxLoginTemplateFactory wxLoginTemplateFactory;

    /**
     * 获取当前生效的配置信息
     */
    @GetMapping("/current")
    public AjaxResult getCurrentConfig() {
        Map<String, String> config = wxLoginTemplateFactory.getCurrentConfig();
        return AjaxResult.success(config);
    }

    /**
     * 刷新配置
     * 在数据库配置更新后，需要调用此接口刷新内存中的配置
     */
    @PostMapping("/refresh")
    public AjaxResult refresh() {
        wxLoginTemplateFactory.refresh();
        return AjaxResult.success("配置刷新成功");
    }

    /**
     * 检查启用状态
     */
    @GetMapping("/enabled")
    public AjaxResult checkEnabled() {
        boolean enabled = wxLoginTemplateFactory.isEnabled();
        return AjaxResult.success("enabled", enabled);
    }
}
