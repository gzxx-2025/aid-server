package com.aid.common.constant;

/**
 * 认证相关常量
 *
 * @author 视觉AID
 */
public class AuthConstants {

    /**
     * 短信验证码缓存key前缀
     */
    public static final String SMS_CODE_KEY = "sms_code:";

    /**
     * 邮箱验证码缓存key前缀
     */
    public static final String EMAIL_CODE_KEY = "email_code:";

    /**
     * 业务场景 - 登录
     */
    public static final String SCENE_LOGIN = "login";

    /**
     * 业务场景 - 绑定
     */
    public static final String SCENE_BIND = "bind";

    /**
     * 业务场景 - 重置密码
     */
    public static final String SCENE_RESET = "reset";

    /**
     * 业务场景 - 解绑
     */
    public static final String SCENE_UNBIND = "unbind";

    /**
     * 业务场景 - 注销
     */
    public static final String SCENE_CANCEL = "cancel";

    /**
     * 绑定/登录类型 - 短信
     */
    public static final String BIND_TYPE_SMS = "sms";

    /**
     * 绑定/登录类型 - 邮箱
     */
    public static final String BIND_TYPE_EMAIL = "email";

    /**
     * 绑定/登录类型 - 微信
     */
    public static final String BIND_TYPE_WECHAT = "wechat";

    /**
     * 手机号正则
     */
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    /**
     * 邮箱正则
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";

    /**
     * 渠道：兼容映射 - phone → sms
     */
    public static final String CHANNEL_ALIAS_PHONE = "phone";

    /**
     * 渠道：兼容映射 - mail → email
     */
    public static final String CHANNEL_ALIAS_MAIL = "mail";

    /**
     * 构建带场景隔离的验证码缓存 key。
     *
     * @param scene    业务场景：login / bind / unbind / reset / cancel
     * @param codeType 渠道类型：sms / email（兼容 phone / mail）
     * @param target   手机号或邮箱
     * @return Redis 缓存 key
     */
    public static String getCodeCacheKey(String scene, String codeType, String target) {
        // 渠道兼容：phone → sms，mail → email
        String channel = normalizeChannel(codeType);
        // 前缀：短信走 SMS_CODE_KEY，邮箱走 EMAIL_CODE_KEY；codeType 校验由调用方负责
        String prefix = BIND_TYPE_EMAIL.equalsIgnoreCase(channel)
                ? "email_code:"
                : "sms_code:";
        // 场景必填：caller 漏传时用 default 兜底，避免 NPE 但仍能区分（不会与正常场景冲撞）
        String safeScene = (scene == null || scene.isEmpty()) ? "default" : scene.toLowerCase();
        return prefix + safeScene + ":" + target;
    }

    /**
     * 渠道兼容映射：phone → sms，mail → email；其余按原值返回。
     */
    public static String normalizeChannel(String channel) {
        if (channel == null) {
            return BIND_TYPE_SMS;
        }
        String c = channel.trim().toLowerCase();
        if (CHANNEL_ALIAS_PHONE.equals(c)) {
            return BIND_TYPE_SMS;
        }
        if (CHANNEL_ALIAS_MAIL.equals(c)) {
            return BIND_TYPE_EMAIL;
        }
        return c;
    }
}
