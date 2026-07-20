package com.aid.common.aid.alipay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 支付宝交易结果
 *
 * @author 视觉AID
 */
@Data
public class AlipayTradeResult {

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
     * 交易状态
     * WAIT_BUYER_PAY-交易创建，等待买家付款
     * TRADE_CLOSED-未付款交易超时关闭，或支付完成后全额退款
     * TRADE_SUCCESS-交易支付成功
     * TRADE_FINISHED-交易结束，不可退款
     */
    private String tradeStatus;

    /**
     * 交易金额
     */
    private BigDecimal totalAmount;

    /**
     * 实收金额
     */
    private BigDecimal receiptAmount;

    /**
     * 买家支付宝账号
     */
    private String buyerLogonId;

    /**
     * 买家支付宝用户ID
     */
    private String buyerUserId;

    /**
     * 交易付款时间
     */
    private Date gmtPayment;

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
     * 支付表单HTML（用于PC网站支付）
     */
    private String formHtml;

    /**
     * 创建成功结果
     */
    public static AlipayTradeResult success() {
        AlipayTradeResult result = new AlipayTradeResult();
        result.setSuccess(true);
        return result;
    }

    /**
     * 创建失败结果
     */
    public static AlipayTradeResult fail(String code, String msg) {
        AlipayTradeResult result = new AlipayTradeResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }

    /**
     * 创建失败结果（带子码）
     */
    public static AlipayTradeResult fail(String code, String msg, String subCode, String subMsg) {
        AlipayTradeResult result = new AlipayTradeResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        result.setSubCode(subCode);
        result.setSubMsg(subMsg);
        return result;
    }
}
