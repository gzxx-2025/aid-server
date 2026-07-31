package com.aid.pay.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 创建订单返回对象
 *
 * @author 视觉AID
 */
@Data
@Builder
public class CreateOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 支付二维码链接
     */
    private String qrCode;


    /**
     * 待支付订单号（hasPendingOrder为true时有值）
     */
    private String pendingOrderNo;

    /**
     * 待支付订单金额
     */
    private BigDecimal pendingPayPrice;

    /**
     * 待支付订单套餐名称
     */
    private String pendingProductName;

    /**
     * 待支付订单剩余时间（秒）
     */
    private Long pendingRemainSeconds;
}
