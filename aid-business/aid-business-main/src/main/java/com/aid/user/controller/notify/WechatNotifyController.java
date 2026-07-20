package com.aid.user.controller.notify;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.notify.wechat.vo.WechatNotifyPreferenceVO;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端微信模板消息推送偏好。
 */
@Tag(name = "微信通知偏好", description = "C端微信模板消息推送偏好设置接口")
@RestController
@RequestMapping("/api/user/wechat-notify")
public class WechatNotifyController extends BaseController
{
    @Resource
    private IWechatNotifyService wechatNotifyService;

    /**
     * 查询当前用户微信推送偏好。
     *
     * @return data: systemEnabled/userEnabled/wechatBound/rules
     */
    @PostMapping("/preference")
    public AjaxResult preference()
    {
        Long userId = SecurityUtils.getUserId();
        WechatNotifyPreferenceVO vo = wechatNotifyService.getPreference(userId);
        return success(vo);
    }

    /**
     * 开启当前用户微信模板消息推送。
     *
     * @return data: 最新偏好状态
     */
    @PostMapping("/enable")
    public AjaxResult enable()
    {
        Long userId = SecurityUtils.getUserId();
        WechatNotifyPreferenceVO vo = wechatNotifyService.enable(userId);
        return success(vo);
    }

    /**
     * 关闭当前用户微信模板消息推送。
     *
     * @return data: 最新偏好状态
     */
    @PostMapping("/disable")
    public AjaxResult disable()
    {
        Long userId = SecurityUtils.getUserId();
        WechatNotifyPreferenceVO vo = wechatNotifyService.disable(userId);
        return success(vo);
    }
}
