package com.aid.common.aid.sms.core;

import com.aid.common.aid.sms.config.SmsConfigManager;
import com.aid.common.aid.sms.config.properties.SmsProperties;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.aid.sms.exception.SmsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 短信模板工厂
 * - 根据配置动态创建短信客户端
 * - 配置通过 SmsConfigManager 管理，手动刷新
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsTemplateFactory {

    private final SmsConfigManager smsConfigManager;

    /**
     * 当前短信客户端实例
     */
    private volatile SmsTemplate currentTemplate;

    /**
     * 当前配置签名（用于判断是否需要重建客户端）
     */
    private volatile String currentConfigSignature;

    /**
     * 获取短信模板实例
     */
    public SmsTemplate getTemplate() {
        SmsProperties properties = smsConfigManager.getSmsProperties();

        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new SmsException("短信服务未启用");
        }

        String signature = buildSignature(properties);

        // 配置变化时重建客户端
        if (!signature.equals(currentConfigSignature)) {
            synchronized (this) {
                if (!signature.equals(currentConfigSignature)) {
                    currentTemplate = createTemplate(smsConfigManager.getProviderType(), properties);
                    currentConfigSignature = signature;
                    log.info("短信客户端已重建: signature={}", signature);
                }
            }
        }

        return currentTemplate;
    }

    /**
     * 发送短信
     */
    public SmsResult send(String phones, String templateId, Map<String, String> param) {
        return getTemplate().send(phones, templateId, param);
    }

    /**
     * 发送验证码（简化方法）
     */
    public SmsResult sendCode(String phone, String code) {
        String templateId = smsConfigManager.getDefaultTemplateId();
        if (templateId == null || templateId.isEmpty()) {
            throw new SmsException("未配置默认短信模板ID");
        }
        String paramName = smsConfigManager.getCodeParamName();
        return send(phone, templateId, Map.of(paramName, code));
    }

    /**
     * 刷新配置（配置更新后调用）
     */
    public void refresh() {
        smsConfigManager.refresh();
        // 清除客户端缓存，下次调用时会重建
        currentTemplate = null;
        currentConfigSignature = null;
        log.info("短信配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return smsConfigManager.getCurrentConfig();
    }

    private SmsTemplate createTemplate(String providerType, SmsProperties properties) {
        log.info("创建短信客户端: providerType={}", providerType);

        switch (providerType.toLowerCase()) {
            case "aliyun":
                checkClassExists("com.aliyun.dysmsapi20170525.Client",
                    "阿里云短信SDK未引入，请在pom.xml中添加依赖:\n" +
                    "<dependency>\n" +
                    "    <groupId>com.aliyun</groupId>\n" +
                    "    <artifactId>dysmsapi20170525</artifactId>\n" +
                    "    <version>2.0.24</version>\n" +
                    "</dependency>");
                return new AliyunSmsTemplate(properties);
            case "tencent":
                checkClassExists("com.tencentcloudapi.sms.v20190711.SmsClient",
                    "腾讯云短信SDK未引入，请在pom.xml中添加依赖:\n" +
                    "<dependency>\n" +
                    "    <groupId>com.tencentcloudapi</groupId>\n" +
                    "    <artifactId>tencentcloud-sdk-java-sms</artifactId>\n" +
                    "    <version>3.1.574</version>\n" +
                    "</dependency>");
                return new TencentSmsTemplate(properties);
            default:
                throw new SmsException("不支持的短信服务商类型: " + providerType);
        }
    }

    /**
     * 检查类是否存在
     */
    private void checkClassExists(String className, String errorMsg) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new SmsException(errorMsg);
        }
    }

    private String buildSignature(SmsProperties properties) {
        return properties.getAccessKeyId() + ":" +
               properties.getEndpoint() + ":" +
               properties.getSignName();
    }
}
