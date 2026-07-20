package com.aid.common.aid.wxlogin.core;

import com.aid.common.aid.wxlogin.config.WxLoginConfigManager;
import com.aid.common.aid.wxlogin.properties.WxLoginProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 微信公众号登录模板工厂
 * - 配置通过 WxLoginConfigManager 管理，手动刷新
 * - 动态创建 WxMpService
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WxLoginTemplateFactory {

    private final WxLoginConfigManager wxLoginConfigManager;

    /**
     * 当前 WxMpService 实例（懒加载）
     */
    private volatile WxMpService wxMpService;

    /**
     * 获取 WxMpService 实例
     * 如果未启用则抛出异常
     */
    public WxMpService getWxMpService() {
        if (!wxLoginConfigManager.isEnabled()) {
            throw new WxLoginException("微信公众号登录服务未启用");
        }
        return getOrCreateWxMpService();
    }

    /**
     * 获取 WxMpService 实例（不检查启用状态）
     */
    public WxMpService getWxMpServiceWithoutCheck() {
        return getOrCreateWxMpService();
    }

    /**
     * 获取或创建 WxMpService
     */
    private WxMpService getOrCreateWxMpService() {
        if (wxMpService == null) {
            synchronized (this) {
                if (wxMpService == null) {
                    wxMpService = createWxMpService();
                }
            }
        }
        return wxMpService;
    }

    /**
     * 创建 WxMpService 实例
     */
    private WxMpService createWxMpService() {
        WxLoginProperties properties = wxLoginConfigManager.getWxLoginProperties();

        WxMpService service = new WxMpServiceImpl();
        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        config.setAppId(properties.getAppId());
        config.setSecret(properties.getSecret());
        config.setToken(properties.getToken());
        config.setAesKey(properties.getEncodingAesKey());
        service.setWxMpConfigStorage(config);

        log.info("创建 WxMpService 实例: appId={}", maskAppId(properties.getAppId()));
        return service;
    }

    /**
     * 刷新配置（配置更新后调用）
     * 重新加载配置并重建 WxMpService
     */
    public void refresh() {
        wxLoginConfigManager.refresh();

        // 清除旧的 Service 实例，下次获取时会重新创建
        synchronized (this) {
            wxMpService = null;
        }

        log.info("微信公众号登录配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        return wxLoginConfigManager.getCurrentConfig();
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        return wxLoginConfigManager.isEnabled();
    }

    /**
     * 脱敏 AppId
     */
    private String maskAppId(String appId) {
        if (appId == null || appId.length() <= 8) {
            return appId;
        }
        return appId.substring(0, 4) + "****" + appId.substring(appId.length() - 4);
    }
}
