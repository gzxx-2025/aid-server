package com.aid.pay.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 充值套餐返回对象
 *
 * @author 视觉AID
 */
@Data
@Builder
public class RechargePackageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 套餐ID
     */
    private Long id;

    /**
     * 套餐名称
     */
    private String packageName;

    /**
     * 获得积分
     */
    private BigDecimal credits;

    /**
     * 原价(元)
     */
    private BigDecimal originalPrice;

    /**
     * 折扣(0.90表示9折)
     */
    private BigDecimal discount;

    /**
     * 实付金额(元)
     */
    private BigDecimal payPrice;

    /**
     * 图标
     */
    private String icon;

    /**
     * 描述
     */
    private String description;
}
