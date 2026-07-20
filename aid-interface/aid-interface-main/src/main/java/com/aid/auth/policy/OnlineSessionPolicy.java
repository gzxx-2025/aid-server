package com.aid.auth.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;

/**
 * C 端「多端在线」策略执行器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class OnlineSessionPolicy {

    /** aid_config 分类标识 */
    private static final String CATEGORY = "login_policy";
    /** 是否允许多端在线 */
    private static final String KEY_ALLOW_MULTI = "allow_multi_online";
    /** 多端在线时的最大会话数 */
    private static final String KEY_MAX_COUNT = "max_online_count";

    @Resource
    private RedisCache redisCache;

    @Resource
    private IAidConfigService aidConfigService;

    /**
     * 执行在线会话策略。任何异常都不应阻断登录主流程，仅记录告警。
     *
     * @param userId       当前登录用户ID
     * @param currentToken 当前会话 token（uuid，无前缀），用于在裁剪时保护当前会话不被误删
     */
    public void enforce(Long userId, String currentToken) {
        if (Objects.isNull(userId)) {
            return;
        }
        try {
            boolean allowMulti = readBool(KEY_ALLOW_MULTI, true);
            int effectiveMax = allowMulti ? Math.max(readInt(KEY_MAX_COUNT, 1), 1) : 1;

            Collection<String> keys = redisCache.scanKeys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (Objects.isNull(keys) || keys.isEmpty()) {
                return;
            }
            // 收集该用户的全部会话
            List<Session> sessions = new ArrayList<>();
            for (String key : keys) {
                LoginUser lu = redisCache.getCacheObject(key);
                if (Objects.nonNull(lu) && Objects.equals(userId, lu.getUserId())) {
                    sessions.add(new Session(key, lu.getLoginTime()));
                }
            }
            if (sessions.size() <= effectiveMax) {
                return;
            }
            // 按登录时间从新到旧排序，保留前 effectiveMax 个，其余清除
            sessions.sort(Comparator.comparingLong((Session s) -> s.loginTime).reversed());
            String currentKey = CacheConstants.LOGIN_TOKEN_KEY + currentToken;
            int kept = 0;
            int removed = 0;
            for (Session s : sessions) {
                // 当前会话始终保留（即使时间排序异常）
                if (Objects.equals(currentKey, s.key)) {
                    continue;
                }
                if (kept < effectiveMax - 1) {
                    kept++;
                    continue;
                }
                redisCache.deleteObject(s.key);
                removed++;
            }
            if (removed > 0) {
                log.info("在线会话策略生效, userId={}, allowMulti={}, max={}, 清除旧会话={}",
                        userId, allowMulti, effectiveMax, removed);
            }
        } catch (Exception e) {
            log.warn("执行在线会话策略异常, userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 全量执行在线会话策略：扫描全部在线会话，按用户ID分组，
     * 对每个用户超过「最大在线会话数」的旧会话按登录时间从新到旧保留前 N 个、其余清除。
     * 用于后台在线用户管理等场景，对历史遗留 / 长期未过期的超限会话做补偿清理，
     * 与登录时的 {@link #enforce(Long, String)} 复用同一套 aid_config(login_policy) 配置口径。
     *
     * @return 本次清理掉的会话数量
     */
    public int enforceAll() {
        try {
            boolean allowMulti = readBool(KEY_ALLOW_MULTI, true);
            // 关闭多端在线时每账号仅允许 1 个会话
            int effectiveMax = allowMulti ? Math.max(readInt(KEY_MAX_COUNT, 1), 1) : 1;

            Collection<String> keys = redisCache.scanKeys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (Objects.isNull(keys) || keys.isEmpty()) {
                return 0;
            }
            // 按用户ID分组收集全部会话
            Map<Long, List<Session>> userSessions = new HashMap<>();
            for (String key : keys) {
                LoginUser lu;
                try {
                    // 兜底转换：兼容历史/异构序列化成 JSONObject 的会话，单条脏数据不影响整体裁剪
                    lu = redisCache.getCacheObject(key, LoginUser.class);
                } catch (Exception e) {
                    log.warn("在线会话解析失败，已跳过, key={}, err={}", key, e.getMessage());
                    continue;
                }
                if (Objects.isNull(lu) || Objects.isNull(lu.getUserId())) {
                    continue;
                }
                userSessions.computeIfAbsent(lu.getUserId(), k -> new ArrayList<>())
                        .add(new Session(key, lu.getLoginTime()));
            }
            int removedTotal = 0;
            for (List<Session> sessions : userSessions.values()) {
                if (sessions.size() <= effectiveMax) {
                    continue;
                }
                // 按登录时间从新到旧排序，保留前 effectiveMax 个，其余清除
                sessions.sort(Comparator.comparingLong((Session s) -> s.loginTime).reversed());
                for (int i = effectiveMax; i < sessions.size(); i++) {
                    redisCache.deleteObject(sessions.get(i).key);
                    removedTotal++;
                }
            }
            if (removedTotal > 0) {
                log.info("全量在线会话策略生效, allowMulti={}, max={}, 清除超限会话={}",
                        allowMulti, effectiveMax, removedTotal);
            }
            return removedTotal;
        } catch (Exception e) {
            log.warn("全量执行在线会话策略异常, err={}", e.getMessage());
            return 0;
        }
    }

    /** 读取布尔配置，缺失或异常时返回默认值 */
    private boolean readBool(String key, boolean defVal) {
        String v = readRaw(key);
        if (v == null) {
            return defVal;
        }
        return "true".equalsIgnoreCase(v) || "Y".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** 读取整型配置，缺失或异常时返回默认值 */
    private int readInt(String key, int defVal) {
        String v = readRaw(key);
        if (v == null) {
            return defVal;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    /** 安全读取 aid_config 值，未配置返回 null（getConfigValue 未命中会抛异常，这里吞掉） */
    private String readRaw(String key) {
        try {
            return aidConfigService.getConfigValue(CATEGORY, key);
        } catch (Exception e) {
            return null;
        }
    }

    /** 会话记录：缓存键 + 登录时间 */
    private static final class Session {
        final String key;
        final long loginTime;

        Session(String key, long loginTime) {
            this.key = key;
            this.loginTime = loginTime;
        }
    }
}
