package com.aid.common.aid.alipay.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 支付宝配置类
 *
 * 配置从数据库动态获取，通过 AlipayConfigManager 管理
 *
 * @author 视觉AID
 */
@Configuration
@ComponentScan(basePackages = "com.aid.common.aid.alipay")
public class AlipayConfig {

}
