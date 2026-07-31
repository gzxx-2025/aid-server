package com.aid.common.constant;

/**
 * 用户引导常量
 *
 * @author 视觉AID
 */
public class OnboardingConstants {

    /** 当前前后端协议版本；结构变更时递增 */
    public static final int ONBOARDING_SCHEMA_VERSION = 1;

    /** Tour 配置缓存名称（Redis Set） */
    public static final String TOUR_CONFIG_CACHE_KEY = "onboarding:tour:configs";

    /** Tour 配置缓存过期时间（秒） */
    public static final long TOUR_CONFIG_CACHE_TTL = 3600;
}
