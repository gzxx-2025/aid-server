package com.aid.aid.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 支付订单对象 aid_pay_order
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_pay_order")
public class AidPayOrder extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商户订单号 */
    @Excel(name = "商户订单号")
    private String orderNo;

    /** 第三方交易号(支付宝/微信) */
    @Excel(name = "第三方交易号(支付宝/微信)")
    private String tradeNo;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 套餐ID */
    @Excel(name = "套餐ID")
    private Long packageId;

    /** 商品名称 */
    @Excel(name = "商品名称")
    private String productName;

    /** 获得积分 */
    @Excel(name = "获得积分")
    private BigDecimal credits;

    /** 原价(元) */
    @Excel(name = "原价(元)")
    private BigDecimal originalPrice;

    /** 折扣 */
    @Excel(name = "折扣")
    private BigDecimal discount;

    /** 实付金额(元) */
    @Excel(name = "实付金额(元)")
    private BigDecimal payPrice;

    /** 支付渠道(alipay支付宝/wxpay微信) */
    @Excel(name = "支付渠道(alipay支付宝/wxpay微信)")
    private String payChannel;

    /** 支付状态(pending待支付/paid已支付/failed失败/closed已关闭/refunded已退款) */
    @Excel(name = "支付状态(pending待支付/paid已支付/failed失败/closed已关闭/refunded已退款)")
    private String payStatus;

    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "支付时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date payTime;

    /** 订单过期时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "订单过期时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date expireTime;

    /** 客户端IP */
    @Excel(name = "客户端IP")
    private String clientIp;

    /** 删除标志(0存在 1删除) */
    private String delFlag;

}
