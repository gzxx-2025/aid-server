package com.aid.common.aid.wxpay.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 微信支付退款结果
 *
 * @author 视觉AID
 */
@Data
public class WxpayRefundResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 微信退款单号
     */
    private String refundId;

    /**
     * 商户退款单号
     */
    private String outRefundNo;

    /**
     * 退款金额（分）
     */
    private Long refundAmount;

    /**
     * 退款状态
     * SUCCESS-退款成功
     * CLOSED-退款关闭
     * PROCESSING-退款处理中
     * ABNORMAL-退款异常
     */
    private String refundStatus;

    /**
     * 错误码
     */
    private String code;

    /**
     * 错误信息
     */
    private String msg;

    /**
     * 创建失败结果
     */
    public static WxpayRefundResult fail(String code, String msg) {
        WxpayRefundResult result = new WxpayRefundResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}
