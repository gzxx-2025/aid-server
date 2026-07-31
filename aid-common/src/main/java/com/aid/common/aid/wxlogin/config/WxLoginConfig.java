package com.aid.common.aid.wxlogin.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 微信公众号登录配置类
 *
 * 配置从数据库动态获取，通过 WxLoginConfigManager 管理
 *
 * @author 视觉AID
 */
@Configuration
@ComponentScan(basePackages = "com.aid.common.aid.wxlogin")
public class WxLoginConfig {

}
