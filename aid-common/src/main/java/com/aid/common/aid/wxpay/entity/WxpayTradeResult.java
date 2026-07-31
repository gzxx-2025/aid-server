package com.aid.common.aid.wxpay.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 微信支付交易结果
 *
 * @author 视觉AID
 */
@Data
public class WxpayTradeResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 商户订单号
     */
    private String outTradeNo;

    /**
     * 微信支付交易号
     */
    private String transactionId;

    /**
     * 交易状态。
     */
    private String tradeState;

    /**
     * 交易状态描述
     */
    private String tradeStateDesc;

    /**
     * 交易金额（分）
     */
    private Integer totalAmount;

    /**
     * 买家openid
     */
    private String openid;

    /**
     * 交易付款时间
     */
    private Date successTime;

    /**
     * 错误码
     */
    private String code;

    /**
     * 错误信息
     */
    private String msg;

    /**
     * 支付二维码链接（Native支付）
     */
    private String codeUrl;

    /**
     * 创建成功结果
     */
    public static WxpayTradeResult success() {
        WxpayTradeResult result = new WxpayTradeResult();
        result.setSuccess(true);
        return result;
    }

    /**
     * 创建失败结果
     */
    public static WxpayTradeResult fail(String code, String msg) {
        WxpayTradeResult result = new WxpayTradeResult();
        result.setSuccess(false);
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}
