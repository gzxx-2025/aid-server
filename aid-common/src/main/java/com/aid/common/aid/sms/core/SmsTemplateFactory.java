package com.aid.common.aid.sms.core;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.sms.config.SmsConfigManager;
import com.aid.common.aid.sms.config.properties.SmsProperties;
import com.aid.common.aid.sms.entity.SmsResult;
import com.aid.common.aid.sms.exception.SmsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
            log.info("短信发送失败: 短信服务未启用");
            throw new SmsException("短信服务未启用");
        }

        String providerType = normalizeProviderType(smsConfigManager.getProviderType());
        String signature = buildSignature(providerType, properties);

        // 配置变化时重建客户端
        if (!signature.equals(currentConfigSignature)) {
            synchronized (this) {
                if (!signature.equals(currentConfigSignature)) {
                    currentTemplate = createTemplate(providerType, properties);
                    currentConfigSignature = signature;
                    log.info("短信客户端已重建: providerType={}", providerType);
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
        if (StrUtil.hasBlank(phone, code)) {
            log.info("短信发送失败: 手机号或验证码为空");
            throw new SmsException("短信参数不完整");
        }
        if ("smsbao".equals(normalizeProviderType(smsConfigManager.getProviderType()))) {
            return send(phone, "", Map.of("code", code));
        }
        String templateId = smsConfigManager.getDefaultTemplateId();
        if (StrUtil.isBlank(templateId)) {
            log.info("短信发送失败: 默认短信模板未配置");
            throw new SmsException("短信模板未配置");
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

        switch (providerType) {
            case "aliyun":
                checkClassExists("com.aliyun.dysmsapi20170525.Client",
                    "aliyun");
                return new AliyunSmsTemplate(properties);
            case "tencent":
                checkClassExists("com.tencentcloudapi.sms.v20190711.SmsClient",
                    "tencent");
                return new TencentSmsTemplate(properties);
            case "smsbao":
                return new SmsBaoSmsTemplate(properties);
            default:
                log.error("短信客户端创建失败: providerType={}", providerType);
                throw new SmsException("短信渠道不支持");
        }
    }

    /**
     * 检查类是否存在
     */
    private void checkClassExists(String className, String providerType) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            log.error("短信SDK未安装: providerType={}, className={}", providerType, className);
            throw new SmsException("短信SDK未安装");
        }
    }

    /** 统一短信厂商编码，空配置安全回退到阿里云。 */
    private String normalizeProviderType(String providerType) {
        return StrUtil.blankToDefault(providerType, "aliyun").trim().toLowerCase(Locale.ROOT);
    }

    private String buildSignature(String providerType, SmsProperties properties) {
        // 指纹包含密钥，确保密钥更新后重建客户端；仅记录哈希值，不把敏感内容写入日志。
        return String.valueOf(Objects.hash(
                providerType,
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret(),
                properties.getSignName(),
                properties.getSdkAppId(),
                properties.getSmsBaoUsername(),
                properties.getSmsBaoApiKey(),
                properties.getSmsBaoProductId(),
                properties.getSmsBaoContentTemplate()));
    }
}
