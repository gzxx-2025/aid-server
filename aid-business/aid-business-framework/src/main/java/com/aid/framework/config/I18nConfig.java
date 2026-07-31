package com.aid.framework.config;

import com.aid.common.constant.Constants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

/**
 * 资源文件配置加载：C 端面向中文用户，强制使用简体中文 Locale，
 * 避免客户端 Accept-Language=en 命中英文资源后返回英文文案；
 * 多语言资源文件保留，未来重启多语言只需换回 AcceptHeaderLocaleResolver。
 *
 * @author AID
 */
@Configuration
public class I18nConfig
{
    @Bean
    public LocaleResolver localeResolver()
    {
        // 固定 Locale 为简体中文：忽略请求头 Accept-Language，统一命中 messages.properties（中文）
        return new FixedLocaleResolver(Constants.DEFAULT_LOCALE);
    }
}
