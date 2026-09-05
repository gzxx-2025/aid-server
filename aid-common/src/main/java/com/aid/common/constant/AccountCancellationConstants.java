package com.aid.common.constant;

/**
 * 账号注销后再次注册限制常量。
 *
 * @author 视觉AID
 */
public final class AccountCancellationConstants {

    public static final String CONFIG_CATEGORY = "account_security";
    public static final String CONFIG_ENABLED = "cancel_re_registration_enabled";
    public static final String CONFIG_DAYS = "cancel_re_registration_days";

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_DAYS = 15;
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 3650;

    public static final String IDENTITY_PHONE = "phone";
    public static final String IDENTITY_EMAIL = "email";
    public static final String IDENTITY_WECHAT_OPENID = "wechat_openid";
    public static final String IDENTITY_WECHAT_UNIONID = "wechat_unionid";

    private AccountCancellationConstants() {
    }
}
