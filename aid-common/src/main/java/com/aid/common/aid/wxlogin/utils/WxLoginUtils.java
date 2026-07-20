package com.aid.common.aid.wxlogin.utils;

import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.common.utils.spring.SpringUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.chanjar.weixin.mp.api.WxMpService;

/**
 * 微信公众号登录工具类
 *
 * @author 视觉AID
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class WxLoginUtils {

    /**
     * 获取 WxMpService 实例
     */
    public static WxMpService getWxMpService() {
        return SpringUtils.getBean(WxLoginTemplateFactory.class).getWxMpService();
    }

    /**
     * 判断微信登录是否启用
     */
    public static boolean isEnabled() {
        return SpringUtils.getBean(WxLoginTemplateFactory.class).isEnabled();
    }

    /**
     * 刷新配置
     */
    public static void refresh() {
        SpringUtils.getBean(WxLoginTemplateFactory.class).refresh();
    }
}
