package com.aid.common.constant;

/**
 * 缓存的key 常量
 *
 * @author 视觉AID
 */
public class CacheConstants
{
    /**
     * 登录用户 redis key
     */
    public static final String LOGIN_TOKEN_KEY = "login_tokens:";

    /**
     * 验证码 redis key
     */
    public static final String CAPTCHA_CODE_KEY = "captcha_codes:";

    /**
     * 参数管理 cache key
     */
    public static final String SYS_CONFIG_KEY = "sys_config:";

    /**
     * 字典管理 cache key
     */
    public static final String SYS_DICT_KEY = "sys_dict:";

    /**
     * 防重提交 redis key
     */
    public static final String REPEAT_SUBMIT_KEY = "repeat_submit:";

    /**
     * 限流 redis key
     */
    public static final String RATE_LIMIT_KEY = "rate_limit:";

    /**
     * 登录账户密码错误次数 redis key
     */
    public static final String PWD_ERR_CNT_KEY = "pwd_err_cnt:";

    /**
     * 邮箱验证码 redis key
     */
    public static final String EMAIL_CODE_KEY = "email_code:";

    /**
     * 短信验证码 redis key
     */
    public static final String SMS_CODE_KEY = "sms_code:";

    /**
     * 密码修改验证码 redis key
     */
    public static final String PWD_CODE_KEY = "pwd_code:";

    /**
     * 官方教程文档地址 redis key（随更新清单静默刷新，后台从缓存读取）
     */
    public static final String UPGRADE_DOC_LINKS_KEY = "upgrade_doc_links";
}
