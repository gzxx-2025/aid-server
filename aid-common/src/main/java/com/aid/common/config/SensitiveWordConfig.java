package com.aid.common.config;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllows;
import com.github.houbb.sensitive.word.support.deny.WordDenys;
import com.aid.common.utils.SensitiveWordUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 敏感词配置类
 *
 * @author 视觉AID
 */
@Configuration
public class SensitiveWordConfig {

    /**
     * 初始化敏感词检测工具
     * 使用默认配置，支持大小写忽略、全角半角忽略、数字格式忽略等
     */
    @Bean
    public SensitiveWordBs sensitiveWordBs() {
        SensitiveWordBs sensitiveWordBs = SensitiveWordBs.newInstance()
                .wordDeny(WordDenys.defaults())
                .wordAllow(WordAllows.defaults())
                // 忽略大小写
                .ignoreCase(true)
                // 忽略半角圆角
                .ignoreWidth(true)
                // 忽略数字的写法
                .ignoreNumStyle(true)
                // 忽略中文的书写格式
                .ignoreChineseStyle(true)
                // 忽略英文的书写格式
                .ignoreEnglishStyle(true)
                // 忽略重复词
                .ignoreRepeat(true)
                // 启用数字检测（手机号/QQ等）
                .enableNumCheck(false)
                // 启用邮箱检测
                .enableEmailCheck(false)
                // 启用链接检测
                .enableUrlCheck(false)
                // 启用IPv4检测
                .enableIpv4Check(false)
                .init();

        // 将初始化的实例注入到工具类
        SensitiveWordUtil.setSensitiveWordBs(sensitiveWordBs);

        return sensitiveWordBs;
    }
}
