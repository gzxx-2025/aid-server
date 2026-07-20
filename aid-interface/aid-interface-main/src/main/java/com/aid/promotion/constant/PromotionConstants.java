package com.aid.promotion.constant;

/**
 * 营销活动相关常量（注册送积分 + 邀请激励）
 *
 * @author 视觉AID
 */
public class PromotionConstants
{
    private PromotionConstants()
    {
    }

    /** 配置分类：注册送积分（aid_config.category） */
    public static final String CONFIG_CATEGORY_REGISTER_BONUS = "register_bonus";

    /** 配置分类：邀请激励（aid_config.category） */
    public static final String CONFIG_CATEGORY_INVITE = "invite";

    /** 配置项：总开关 */
    public static final String CONFIG_KEY_ENABLED = "enabled";

    /** 配置项：注册赠送积分数量 */
    public static final String CONFIG_KEY_AMOUNT = "amount";

    /** 配置项：手机号注册是否参与 */
    public static final String CONFIG_KEY_SMS_ENABLED = "sms_enabled";

    /** 配置项：邮箱注册是否参与（邮箱免费注册易被薅羊毛，可独立关闭） */
    public static final String CONFIG_KEY_EMAIL_ENABLED = "email_enabled";

    /** 配置项：微信注册是否参与 */
    public static final String CONFIG_KEY_WECHAT_ENABLED = "wechat_enabled";

    /** 配置项：充值返佣比例(%) */
    public static final String CONFIG_KEY_REBATE_RATIO = "rebate_ratio";

    /** 配置项：单笔订单返佣积分上限（0为不限） */
    public static final String CONFIG_KEY_REBATE_MAX_PER_ORDER = "rebate_max_per_order";

    /** 余额流水业务类型：注册赠送 */
    public static final String BIZ_TYPE_REGISTER_BONUS = "register_bonus";

    /** 余额流水业务名称：注册赠送 */
    public static final String BIZ_NAME_REGISTER_BONUS = "注册赠送积分";

    /** 余额流水业务类型：邀请返佣 */
    public static final String BIZ_TYPE_INVITE_REBATE = "invite_rebate";

    /** 余额流水业务名称：邀请返佣 */
    public static final String BIZ_NAME_INVITE_REBATE = "邀请好友充值返佣";

    /** 注册赠送幂等 traceId 前缀（register_bonus_{userId}，一人一次） */
    public static final String TRACE_PREFIX_REGISTER_BONUS = "register_bonus_";

    /** 邀请返佣幂等 traceId 后缀（{orderNo}_INVITE） */
    public static final String REBATE_TRACE_SUFFIX = "_INVITE";

    /** 邀请返佣退款扣回幂等 traceId 后缀（{orderNo}_INVITE_RFD） */
    public static final String REBATE_REVOKE_TRACE_SUFFIX = "_INVITE_RFD";

    /** 返佣记录状态：已发放 */
    public static final String REBATE_STATUS_GRANTED = "granted";

    /** 返佣记录状态：已撤回（订单退款扣回） */
    public static final String REBATE_STATUS_REVOKED = "revoked";

    /** 邀请关系状态：正常 */
    public static final String RELATION_STATUS_NORMAL = "0";

    /** 邀请关系状态：禁用（风控处置，不再产生返佣） */
    public static final String RELATION_STATUS_DISABLED = "1";

    /** 邀请码字符集（大写字母+数字，去除易混淆的 0/O/1/I） */
    public static final String INVITE_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 邀请码长度 */
    public static final int INVITE_CODE_LENGTH = 8;

    /** 邀请码生成最大重试次数（唯一冲突时重新生成） */
    public static final int INVITE_CODE_MAX_RETRY = 10;
}
