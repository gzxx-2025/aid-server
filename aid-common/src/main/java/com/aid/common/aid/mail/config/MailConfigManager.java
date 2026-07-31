package com.aid.common.aid.mail.config;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.mail.utils.MailAccount;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 邮箱配置管理器
 * - 配置从数据库加载到内存
 * - 手动刷新机制，避免频繁查询数据库
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailConfigManager {

    private final ConfigService configService;

    /**
     * 内存缓存的所有配置
     */
    @Getter
    private final Map<String, String> configCache = new HashMap<>();

    /**
     * 当前使用的邮箱账户
     */
    @Getter
    private MailAccount currentMailAccount;

    /**
     * 初始化标识
     */
    private volatile boolean initialized = false;

    /**
     * 初始化配置（首次使用时调用）
     */
    public void init() {
        if (!initialized) {
            refresh();
        }
    }

    /**
     * 刷新配置（从数据库重新加载）
     * 在配置页面点击"刷新配置"时调用
     */
    public void refresh() {
        log.info("刷新邮箱配置...");

        // 一次性获取mail分类的所有配置
        Map<String, String> allConfig = configService.getConfigValues("mail");
        configCache.clear();
        if (allConfig != null) {
            configCache.putAll(allConfig);
        }

        // 构建MailAccount对象
        currentMailAccount = buildMailAccount();

        initialized = true;
        log.info("邮箱配置刷新完成: host={}, from={}", currentMailAccount.getHost(), currentMailAccount.getFrom());
    }

    /**
     * 获取邮箱账户
     */
    public MailAccount getMailAccount() {
        init();
        return currentMailAccount;
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        init();
        return Boolean.parseBoolean(getCacheValue("enabled", "false"));
    }

    /**
     * 获取当前生效的配置（供前端展示）
     */
    public Map<String, String> getCurrentConfig() {
        init();
        Map<String, String> result = new HashMap<>(configCache);
        // 脱敏敏感信息
        if (result.containsKey("pass")) {
            String pass = result.get("pass");
            if (pass != null && pass.length() > 2) {
                result.put("pass", pass.substring(0, 2) + "****");
            }
        }
        return result;
    }
    private String getCacheValue(String key, String defaultValue) {
        String value = configCache.get(key);
        return value != null ? value : defaultValue;
    }

    private int getCacheInt(String key, int defaultValue) {
        String value = configCache.get(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private MailAccount buildMailAccount() {
        MailAccount account = new MailAccount();
        account.setHost(getCacheValue("host", ""));
        account.setPort(getCacheInt("port", 465));
        account.setAuth(true);
        account.setFrom(getCacheValue("from", ""));
        account.setUser(getCacheValue("user", ""));
        account.setPass(getCacheValue("pass", ""));
        account.setSocketFactoryPort(getCacheInt("port", 465));
        account.setStarttlsEnable(true);
        account.setSslEnable(true);
        account.setTimeout(0);
        account.setConnectionTimeout(0);
        return account;
    }
}
