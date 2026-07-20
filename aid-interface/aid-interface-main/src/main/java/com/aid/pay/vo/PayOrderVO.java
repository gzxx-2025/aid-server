package com.aid.pay.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单状态返回对象
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PayOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 商品名称
     */
    private String productName;

    /**
     * 获得积分
     */
    private BigDecimal credits;

    /**
     * 实付金额(元)
     */
    private BigDecimal payPrice;

    /**
     * 支付状态
     */
    private String payStatus;

    /**
     * 支付时间
     */
    private Date payTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 是否可以重新支付（待支付且未超时）
     */
    private Boolean canRepay;
}
