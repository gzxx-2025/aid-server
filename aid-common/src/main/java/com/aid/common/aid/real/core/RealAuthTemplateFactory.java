package com.aid.common.aid.real.core;

import com.aid.common.aid.real.config.RealAuthConfigManager;
import com.aid.common.aid.real.entity.RealAuthResult;
import com.aid.common.aid.real.exception.RealAuthException;
import com.aid.common.aid.real.properties.RealAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实名认证模板工厂
 * - 根据配置选择二要素或三要素认证
 * - 提供统一的认证入口
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealAuthTemplateFactory {

    private final RealAuthConfigManager configManager;

    /**
     * 二要素认证服务（懒加载）
     */
    private volatile RealAuthService twoFactorService;

    /**
     * 三要素认证服务（懒加载）
     */
    private volatile RealAuthService threeFactorService;

    /**
     * 执行实名认证
     *
     * @param realName 真实姓名
     * @param idCard   身份证号
     * @param phone    手机号（三要素时需要）
     * @return 认证结果
     */
    public RealAuthResult verify(String realName, String idCard, String phone) {
        RealAuthProperties properties = configManager.getRealAuthProperties();

        // 检查是否启用
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new RealAuthException("实名认证功能未启用");
        }

        // 检查 AppCode 是否配置
        if (properties.getAppCode() == null || properties.getAppCode().isEmpty()) {
            throw new RealAuthException("实名认证未配置AppCode");
        }

        // 获取当前配置的认证类型
        String authType = properties.getAuthType();
        RealAuthService service = getService(authType, properties);

        if (service == null) {
            throw new RealAuthException("未找到有效的实名认证服务: " + authType);
        }

        return service.verify(realName, idCard, phone);
    }

    /**
     * 获取实名认证服务
     */
    private RealAuthService getService(String authType, RealAuthProperties properties) {
        if ("twoFactor".equals(authType)) {
            if (twoFactorService == null) {
                synchronized (this) {
                    if (twoFactorService == null) {
                        twoFactorService = new TwoFactorAuthService(properties);
                    }
                }
            }
            return twoFactorService;
        } else if ("threeFactor".equals(authType)) {
            if (threeFactorService == null) {
                synchronized (this) {
                    if (threeFactorService == null) {
                        threeFactorService = new ThreeFactorAuthService(properties);
                    }
                }
            }
            return threeFactorService;
        }
        return null;
    }

    /**
     * 刷新配置
     */
    public void refresh() {
        configManager.refresh();
        // 清除旧的服务实例，下次使用时会重新创建
        twoFactorService = null;
        threeFactorService = null;
        log.info("实名认证配置已刷新");
    }

    /**
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return configManager.getCurrentConfig();
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        return configManager.isEnabled();
    }

    /**
     * 获取当前配置的认证类型
     */
    public String getAuthType() {
        RealAuthProperties properties = configManager.getRealAuthProperties();
        return properties.getAuthType();
    }

    /**
     * 判断是否需要手机号
     */
    public boolean needPhone() {
        RealAuthProperties properties = configManager.getRealAuthProperties();
        return properties.needPhone();
    }
}
