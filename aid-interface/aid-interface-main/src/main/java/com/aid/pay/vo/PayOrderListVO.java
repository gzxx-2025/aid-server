package com.aid.pay.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单列表返回对象
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PayOrderListVO implements Serializable {

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
     * 原价(元)
     */
    private BigDecimal originalPrice;

    /**
     * 折扣
     */
    private BigDecimal discount;

    /**
     * 实付金额(元)
     */
    private BigDecimal payPrice;

    /**
     * 支付渠道
     */
    private String payChannel;

    /**
     * 支付状态
     */
    private String payStatus;

    /**
     * 支付时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date payTime;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
