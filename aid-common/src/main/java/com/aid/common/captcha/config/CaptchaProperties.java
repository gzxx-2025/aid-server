package com.aid.common.captcha.config;

/**
 * 行为验证码（tianai-captcha）相关常量与默认值。
 *
 * 全部行为由 aid_config(category=captcha) 动态驱动，本类仅集中 key 名与安全默认值，
 * 避免散落的魔法字符串。配置缺失时一律使用本类默认值兜底，保证可用性优先（fail-open）。
 *
 * @author 视觉AID
 */
public final class CaptchaProperties {

    private CaptchaProperties() {
    }

    /** aid_config 配置分类 */
    public static final String CATEGORY = "captcha";

    /** 配置项：总开关（true/false） */
    public static final String KEY_ENABLED = "enabled";

    /** 配置项：验证码类型（SLIDER/ROTATE/WORD_IMAGE_CLICK/CONCAT/RANDOM） */
    public static final String KEY_TYPE = "type";

    /** 配置项：受保护场景，逗号分隔（如 login,sendCode） */
    public static final String KEY_PROTECTED_SCENES = "protected_scenes";

    /** 配置项：背景图 CDN 地址，逗号分隔；为空则不开启 */
    public static final String KEY_BACKGROUND_URLS = "background_urls";

    /** 配置项：二次验证 token 有效期（秒） */
    public static final String KEY_TOKEN_EXPIRE_SECONDS = "token_expire_seconds";

    /** 配置项：验证码数据有效期（秒） */
    public static final String KEY_CAPTCHA_EXPIRE_SECONDS = "captcha_expire_seconds";

    /** 默认：总开关关闭（上线零影响，运营配齐背景图后再开） */
    public static final boolean DEFAULT_ENABLED = false;

    /** 默认：滑块 */
    public static final String DEFAULT_TYPE = "SLIDER";

    /** 默认：受保护场景为登录与发码 */
    public static final String DEFAULT_PROTECTED_SCENES = "login,sendCode";

    /** 默认：token 有效期 300 秒 */
    public static final int DEFAULT_TOKEN_EXPIRE_SECONDS = 300;

    /** 默认：验证码数据有效期 120 秒 */
    public static final int DEFAULT_CAPTCHA_EXPIRE_SECONDS = 120;

    /** 随机类型标识 */
    public static final String TYPE_RANDOM = "RANDOM";

    /** 场景：登录 */
    public static final String SCENE_LOGIN = "login";

    /** 场景：发送验证码 */
    public static final String SCENE_SEND_CODE = "sendCode";

    /** 登录类型：短信验证码登录 */
    public static final String LOGIN_TYPE_SMS = "sms";

    /** 登录类型：邮箱验证码登录 */
    public static final String LOGIN_TYPE_EMAIL = "email";

    /** 登录类型：微信扫码登录 */
    public static final String LOGIN_TYPE_WECHAT = "wechat";

    /** Redis：验证码数据缓存 key 前缀（tianai CacheStore 使用） */
    public static final String REDIS_CAPTCHA_PREFIX = "captcha:tac";

    /** Redis：二次验证 token 缓存 key 前缀 */
    public static final String REDIS_TOKEN_PREFIX = "captcha:token:";

    /** 请求头：携带二次验证 token */
    public static final String HEADER_CAPTCHA_TOKEN = "captcha-token";

    /** 配置快照短缓存毫秒数，避免每次请求打库 */
    public static final long CONFIG_CACHE_MILLIS = 5000L;
}
