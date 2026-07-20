package com.aid.common.aid.sms.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 短信配置类
 *
 * 改为动态获取配置，不再使用 @ConditionalOnProperty 在启动时固定
 * 通过 SmsTemplateFactory 运行时动态选择短信服务商
 *
 * @author 视觉AID
 */
@Configuration
@ComponentScan(basePackages = "com.aid.common.aid.sms")
public class SmsConfig {

}
