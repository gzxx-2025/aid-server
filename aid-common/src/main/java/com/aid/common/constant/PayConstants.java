package com.aid.common.constant;

/**
 * 支付相关常量
 *
 * @author 视觉AID
 */
public class PayConstants {

    private PayConstants() {
    }
    /**
     * 支付宝
     */
    public static final String CHANNEL_ALIPAY = "alipay";

    /**
     * 微信支付
     */
    public static final String CHANNEL_WXPAY = "wxpay";

    /**
     * 微信支付交易状态-成功
     */
    public static final String WXPAY_TRADE_SUCCESS = "SUCCESS";
    /**
     * 待支付
     */
    public static final String STATUS_PENDING = "pending";

    /**
     * 已支付
     */
    public static final String STATUS_PAID = "paid";

    /**
     * 支付失败
     */
    public static final String STATUS_FAILED = "failed";

    /**
     * 已关闭
     */
    public static final String STATUS_CLOSED = "closed";

    /**
     * 已退款
     */
    public static final String STATUS_REFUNDED = "refunded";
    /**
     * 充值
     */
    public static final String BALANCE_CHANGE_TYPE_RECHARGE = "recharge";

    /**
     * 充值业务类型
     */
    public static final String BALANCE_BIZ_TYPE_RECHARGE = "recharge";

    /**
     * 充值业务名称
     */
    public static final String BALANCE_BIZ_NAME_RECHARGE = "余额充值";
    /**
     * 订单过期时间（分钟）
     */
    public static final int ORDER_EXPIRE_MINUTES = 30;

    /**
     * 订单号前缀
     */
    public static final String ORDER_NO_PREFIX = "R";
    /**
     * 支付回调锁前缀
     */
    public static final String LOCK_PAY_NOTIFY = "pay:notify:lock:";

    /**
     * 用户下单锁前缀（同一用户下单受理串行化，防并发重复建单）
     */
    public static final String LOCK_CREATE_ORDER = "pay:create:lock:";

    /**
     * 锁等待时间（秒）- 等待获取锁
     */
    public static final long LOCK_WAIT_TIME = 5;

    /**
     * 锁持有时间（秒）
     */
    public static final long LOCK_LEASE_TIME = 30;
    /**
     * C 端轮询主动查单节流 key 前缀。
     * 待支付未超时时也主动向渠道查单，避免回调丢失/延迟导致"付了钱状态不更新"。
     * 通过该 key 做节流，限制同一订单在 {@link #QUERY_THROTTLE_SECONDS} 秒内最多主动查一次，
     * 避免触发渠道查单 QPS 限制。
     */
    public static final String QUERY_THROTTLE_PREFIX = "pay:query:throttle:";

    /**
     * 主动查单节流窗口（秒）：同一订单该窗口内最多主动查渠道一次
     */
    public static final long QUERY_THROTTLE_SECONDS = 10;
    /**
     * 退款积分扣回幂等 traceId 后缀（用于 adminAdjust 幂等键）
     */
    public static final String REFUND_DEDUCT_TRACE_SUFFIX = "_RFD";

    /**
     * 退款业务名称（写入余额流水）
     */
    public static final String BALANCE_BIZ_NAME_REFUND = "订单退款扣回积分";
}
