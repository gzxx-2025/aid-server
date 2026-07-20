package com.aid.common.captcha.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.captcha.config.CaptchaProperties;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 行为验证码动态配置读取服务。
 *
 * 所有配置来自 aid_config(category=captcha)，带 ~5 秒短缓存快照减少打库；
 * 任何读取异常一律走安全默认值并 log.error，保证可用性优先（fail-open）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class CaptchaConfigService {

    @Resource
    private ConfigService configService;

    /** 配置快照的原子引用，~5 秒刷新一次 */
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    /**
     * 获取（必要时刷新）配置快照。
     */
    private Snapshot snapshot() {
        Snapshot cur = snapshotRef.get();
        long now = System.currentTimeMillis();
        // 命中短缓存直接返回
        if (cur != null && (now - cur.loadTime) < CaptchaProperties.CONFIG_CACHE_MILLIS) {
            return cur;
        }
        // 重新加载（多线程并发时多加载几次无副作用）
        Snapshot fresh = load();
        snapshotRef.set(fresh);
        return fresh;
    }

    /**
     * 从 aid_config 加载全部 captcha 配置，逐项兜底。
     */
    private Snapshot load() {
        Snapshot s = new Snapshot();
        s.loadTime = System.currentTimeMillis();
        s.enabled = readBool(CaptchaProperties.KEY_ENABLED, CaptchaProperties.DEFAULT_ENABLED);
        s.type = readString(CaptchaProperties.KEY_TYPE, CaptchaProperties.DEFAULT_TYPE);
        s.protectedScenes = parseList(readString(CaptchaProperties.KEY_PROTECTED_SCENES,
                CaptchaProperties.DEFAULT_PROTECTED_SCENES));
        s.backgroundUrls = parseList(readString(CaptchaProperties.KEY_BACKGROUND_URLS, ""));
        s.tokenExpireSeconds = readInt(CaptchaProperties.KEY_TOKEN_EXPIRE_SECONDS,
                CaptchaProperties.DEFAULT_TOKEN_EXPIRE_SECONDS);
        s.captchaExpireSeconds = readInt(CaptchaProperties.KEY_CAPTCHA_EXPIRE_SECONDS,
                CaptchaProperties.DEFAULT_CAPTCHA_EXPIRE_SECONDS);
        return s;
    }

    /**
     * 是否开启验证（异常一律按未开启，业务放行）。
     */
    public boolean isEnabled() {
        return snapshot().enabled;
    }

    /**
     * 验证是否就绪：开启 且 已配置背景图。
     * 未就绪即降级为不拦截（无图不开启）。
     */
    public boolean isReady() {
        Snapshot s = snapshot();
        return s.enabled && CollectionUtil.isNotEmpty(s.backgroundUrls);
    }

    /**
     * 指定场景是否受保护。
     */
    public boolean isSceneProtected(String scene) {
        if (StrUtil.isBlank(scene)) {
            return false;
        }
        return snapshot().protectedScenes.contains(scene);
    }

    /**
     * 解析本次生成应使用的验证码类型。
     * RANDOM 时从就绪类型集合随机选择一种。
     */
    public String resolveType(List<String> readyTypes) {
        String type = snapshot().type;
        // 非随机：直接返回配置值
        if (!CaptchaProperties.TYPE_RANDOM.equalsIgnoreCase(type)) {
            return type;
        }
        // 随机：从就绪类型里挑一个，集合为空兜底为滑块
        if (CollectionUtil.isEmpty(readyTypes)) {
            return CaptchaProperties.DEFAULT_TYPE;
        }
        return readyTypes.get(ThreadLocalRandom.current().nextInt(readyTypes.size()));
    }

    /**
     * 原始配置的验证码类型（不做随机解析，供状态查询展示）。
     */
    public String rawType() {
        return snapshot().type;
    }

    /**
     * 当前背景图 URL 列表（不可变副本）。
     */
    public List<String> backgroundUrls() {
        return Collections.unmodifiableList(snapshot().backgroundUrls);
    }

    /**
     * 背景图列表签名，供 CaptchaService 检测变更后刷新资源。
     */
    public String backgroundSignature() {
        return String.join("|", snapshot().backgroundUrls);
    }

    /**
     * token 有效期（秒）。
     */
    public int tokenExpireSeconds() {
        return snapshot().tokenExpireSeconds;
    }

    /**
     * 验证码数据有效期（秒）。
     */
    public int captchaExpireSeconds() {
        return snapshot().captchaExpireSeconds;
    }
    private String readString(String key, String def) {
        try {
            String val = configService.getConfigValue(CaptchaProperties.CATEGORY, key);
            return StrUtil.isBlank(val) ? def : val.trim();
        } catch (Exception e) {
            // 未配置或读取异常：用默认值，仅 debug 级别留痕避免刷屏
            log.debug("读取验证码配置失败,使用默认值: key={}, default={}", key, def);
            return def;
        }
    }

    private boolean readBool(String key, boolean def) {
        String val = readString(key, String.valueOf(def));
        return Boolean.parseBoolean(val);
    }

    private int readInt(String key, int def) {
        String val = readString(key, String.valueOf(def));
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            log.error("验证码数值配置非法,使用默认值: key={}, value={}, default={}", key, val, def);
            return def;
        }
    }

    /**
     * 逗号分隔字符串解析为去空列表。
     */
    private List<String> parseList(String csv) {
        if (StrUtil.isBlank(csv)) {
            return new ArrayList<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * 配置快照。
     */
    private static class Snapshot {
        long loadTime;
        boolean enabled;
        String type;
        List<String> protectedScenes = new ArrayList<>();
        List<String> backgroundUrls = new ArrayList<>();
        int tokenExpireSeconds;
        int captchaExpireSeconds;
    }
}
