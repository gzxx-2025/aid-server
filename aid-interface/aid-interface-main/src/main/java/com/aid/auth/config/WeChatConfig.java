package com.aid.auth.config;

import com.aid.common.aid.wxlogin.config.WxLoginConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 微信公众号配置
 *
 * WxMpService 由 WxLoginTemplateFactory 动态管理，配置从数据库 aid_config 表读取（category = 'wxLogin'）。
 *
 * @author 视觉AID
 */
@Configuration
@Import(WxLoginConfig.class)
public class WeChatConfig {

    // WxMpService 由 WxLoginTemplateFactory 创建和管理
    // 使用方式：注入 WxLoginTemplateFactory，调用 getWxMpService() 获取

}
