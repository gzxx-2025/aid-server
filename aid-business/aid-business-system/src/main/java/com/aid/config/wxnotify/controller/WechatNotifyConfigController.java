package com.aid.config.wxnotify.controller;

import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.config.wxnotify.dto.WechatNotifyTestRequest;
import com.aid.notify.wechat.config.WechatNotifyConfig;
import com.aid.notify.wechat.service.IWechatNotifyConfigService;
import com.aid.notify.wechat.service.IWechatNotifyService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 微信公众号模板消息推送配置。
 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/wxnotify")
@RequiredArgsConstructor
public class WechatNotifyConfigController extends BaseController
{
    private final IWechatNotifyConfigService wechatNotifyConfigService;
    private final IWechatNotifyService wechatNotifyService;
    private final WxLoginTemplateFactory wxLoginTemplateFactory;

    /**
     * 读取微信模板消息推送配置。
     *
     * @return data: WechatNotifyConfig
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/config")
    public AjaxResult getConfig()
    {
        return success(wechatNotifyConfigService.getConfig());
    }

    /**
     * 保存微信模板消息推送配置。
     *
     * @param request 配置表单数据
     * @return data: 保存后的规范化配置
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "微信公众号推送配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    public AjaxResult saveConfig(@RequestBody WechatNotifyConfig request)
    {
        if (Objects.isNull(request))
        {
            return AjaxResult.error("参数不能为空");
        }
        return success(wechatNotifyConfigService.saveConfig(request));
    }

    /**
     * 读取微信推送准备状态和缺失配置项。
     *
     * @return data: WechatNotifyStatusVO
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/status")
    public AjaxResult status()
    {
        return success(wechatNotifyConfigService.getStatus());
    }

    /**
     * 获取公众号已选用模板列表。
     *
     * @return data: 微信已选模板列表
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/templates")
    public AjaxResult templates()
    {
        try
        {
            Object templates = wxLoginTemplateFactory.getWxMpServiceWithoutCheck()
                    .getTemplateMsgService()
                    .getAllPrivateTemplate();
            return success(templates);
        }
        catch (Exception e)
        {
            log.error("获取微信公众号模板列表失败", e);
            return AjaxResult.error("模板列表失败");
        }
    }

    /**
     * 测试发送模板消息。
     *
     * @param request openid + eventType
     * @return data: 微信发送响应
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "微信公众号推送测试", businessType = BusinessType.OTHER)
    @PostMapping("/test")
    public AjaxResult test(@RequestBody WechatNotifyTestRequest request)
    {
        if (Objects.isNull(request) || StrUtil.isBlank(request.getOpenid()))
        {
            return AjaxResult.error("OpenID不能为空");
        }
        return success(wechatNotifyService.testSend(request.getOpenid(), request.getEventType()));
    }
}
