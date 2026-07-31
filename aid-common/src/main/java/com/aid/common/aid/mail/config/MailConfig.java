package com.aid.common.aid.mail.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 邮箱配置类
 *
 * 配置从数据库动态获取，通过 MailConfigManager 管理
 *
 * @author 视觉AID
 */
@Configuration
@ComponentScan(basePackages = "com.aid.common.aid.mail")
public class MailConfig {

}
