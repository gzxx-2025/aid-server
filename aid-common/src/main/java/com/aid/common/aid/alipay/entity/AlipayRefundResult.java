package com.aid.common.aid.alipay.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 支付宝退款结果
 *
 * @author 视觉AID
 */
@Data
public class AlipayRefundResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 支付宝交易号
     */
    private String tradeNo;

    /**
     * 退款请求号
     */
    private String outRequestNo;

    /**
     * 退款金额
     */
    private BigDecimal refundAmount;

    /**
     * 退款状态
     * Y-退款成功
     * N-退款失败
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
     * 错误子码
     */
    private String subCode;

    /**
     * 错误子信息
     */
    private String subMsg;

    /**
     * 创建成功结果
     */
    public static AlipayRefundResult success() {
        AlipayRefundResult result = new AlipayRefundResult();
        result.setSuccess(true);
        return result;
    }

    /**
     * 创建失败结果
     */
    public static AlipayRefundResult fail(String code, String msg) {
        AlipayRefundResult result = new AlipayRefundResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    /**
     * 创建失败结果（带子码）
     */
    public static AlipayRefundResult fail(String code, String msg, String subCode, String subMsg) {
        AlipayRefundResult result = new AlipayRefundResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        result.setSubCode(subCode);
        result.setSubMsg(subMsg);
        return result;
    }
}
