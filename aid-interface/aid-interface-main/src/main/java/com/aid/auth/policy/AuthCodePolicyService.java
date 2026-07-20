package com.aid.auth.policy;

import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * 验证码策略读取服务。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AuthCodePolicyService {

    /** 渠道：短信 */
    public static final String CHANNEL_SMS = "sms";

    /** 渠道：邮箱 */
    public static final String CHANNEL_EMAIL = "email";

    /** 短信策略 category（与现有短信渠道配置同分类） */
    private static final String CATEGORY_SMS = "sms";

    /** 邮箱策略 category（与现有邮箱渠道配置同分类） */
    private static final String CATEGORY_MAIL = "mail";    /** 配置名：验证码长度 */
    private static final String KEY_CODE_LENGTH = "code_length";

    /** 配置名：有效期（分钟） */
    private static final String KEY_CODE_EXPIRE_MINUTES = "code_expire_minutes";

    /** 配置名：发送间隔（秒） */
    private static final String KEY_SEND_INTERVAL_SECONDS = "send_interval_seconds";

    /** 配置名：每日上限（次） */
    private static final String KEY_DAILY_LIMIT = "daily_limit";

    // -------- 内置默认值（DB 缺失时兜底用） --------

    /** 兜底：验证码长度 6 位 */
    private static final int DEFAULT_CODE_LENGTH = 6;

    /** 兜底：有效期 5 分钟 */
    private static final int DEFAULT_CODE_EXPIRE_MINUTES = 5;

    /** 兜底：发送间隔 120 秒 */
    private static final int DEFAULT_SEND_INTERVAL_SECONDS = 120;

    /** 兜底：每日上限 10 次 */
    private static final int DEFAULT_DAILY_LIMIT = 10;

    @Resource
    private ConfigService aidConfigService;

    /**
     * 按渠道获取验证码策略
     *
     * @param channel {@link #CHANNEL_SMS} 或 {@link #CHANNEL_EMAIL}（不区分大小写）
     * @return 已就绪的策略对象，**任何字段都保证有合法默认值**
     */
    public AuthCodePolicy getPolicy(String channel) {
        String normalized = normalizeChannel(channel);
        String category = CHANNEL_SMS.equals(normalized) ? CATEGORY_SMS : CATEGORY_MAIL;

        Map<String, String> raw = readCategorySafely(category);

        return AuthCodePolicy.builder()
                .channel(normalized)
                .codeLength(parsePositiveIntOrDefault(raw, KEY_CODE_LENGTH, DEFAULT_CODE_LENGTH, 4, 8))
                .codeExpireMinutes(parsePositiveIntOrDefault(raw, KEY_CODE_EXPIRE_MINUTES,
                        DEFAULT_CODE_EXPIRE_MINUTES, 1, 60))
                .sendIntervalSeconds(parsePositiveIntOrDefault(raw, KEY_SEND_INTERVAL_SECONDS,
                        DEFAULT_SEND_INTERVAL_SECONDS, 1, 24 * 60 * 60))
                .dailyLimit(parseIntOrDefault(raw, KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT))
                .build();
    }

    /**
     * 渠道标准化：sms / phone / SMS 都归一为 {@code sms}；email / EMAIL 归一为 {@code email}。
     * 其它值抛业务异常。
     */
    private String normalizeChannel(String channel) {
        if (StrUtil.isBlank(channel)) {
            throw new ServiceException("渠道不能为空");
        }
        String c = channel.trim().toLowerCase();
        if ("sms".equals(c) || "phone".equals(c)) {
            return CHANNEL_SMS;
        }
        if ("email".equals(c) || "mail".equals(c)) {
            return CHANNEL_EMAIL;
        }
        throw new ServiceException("渠道不支持");
    }

    /**
     * 读取一个分类下全部 key/value，捕获所有底层异常并返回空 Map，
     * 保证策略读取永不抛 RuntimeException 影响主链路（发码 / 登录）。
     */
    private Map<String, String> readCategorySafely(String category) {
        try {
            Map<String, String> map = aidConfigService.getConfigValues(category);
            return Objects.nonNull(map) ? map : Map.of();
        } catch (Exception e) {
            log.warn("读取验证码策略失败，回退默认值: category={}, msg={}", category, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 取正整数，缺失 / 非数字 / 越界 → 用默认值。
     * @param min / max 区间 inclusive；越界落库的脏值不让上线，避免 0 分钟过期等灾难
     */
    private int parsePositiveIntOrDefault(Map<String, String> raw, String key,
                                           int defaultVal, int min, int max) {
        String v = raw.get(key);
        if (StrUtil.isBlank(v)) {
            return defaultVal;
        }
        try {
            int parsed = Integer.parseInt(v.trim());
            if (parsed < min || parsed > max) {
                log.warn("验证码策略值越界，回退默认: key={}, value={}, range=[{},{}], default={}",
                        key, parsed, min, max, defaultVal);
                return defaultVal;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("验证码策略值非数字，回退默认: key={}, raw={}, default={}", key, v, defaultVal);
            return defaultVal;
        }
    }

    /**
     * 取整数（允许 ≤ 0，表示"不限"），缺失 / 非数字 → 用默认值。
     * 主要给 dailyLimit 用：管理员若想关闭日上限可填 0 / -1。
     */
    private int parseIntOrDefault(Map<String, String> raw, String key, int defaultVal) {
        String v = raw.get(key);
        if (StrUtil.isBlank(v)) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("验证码策略值非数字，回退默认: key={}, raw={}, default={}", key, v, defaultVal);
            return defaultVal;
        }
    }
}
