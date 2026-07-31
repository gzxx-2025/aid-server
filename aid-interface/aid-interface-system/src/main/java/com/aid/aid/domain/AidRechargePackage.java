package com.aid.aid.domain;

import java.math.BigDecimal;
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
 * 充值套餐配置对象 aid_recharge_package
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_recharge_package")
public class AidRechargePackage extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 套餐名称 */
    @Excel(name = "套餐名称")
    private String packageName;

    /** 获得积分 */
    @Excel(name = "获得积分")
    private BigDecimal credits;

    /** 原价(元) */
    @Excel(name = "原价(元)")
    private BigDecimal originalPrice;

    /** 折扣(0.90表示9折, 1.00表示无折扣) */
    @Excel(name = "折扣(0.90表示9折, 1.00表示无折扣)")
    private BigDecimal discount;

    /** 实付金额(元, original_price * discount) */
    @Excel(name = "实付金额(元, original_price * discount)")
    private BigDecimal payPrice;

    /** 图标 */
    @Excel(name = "图标")
    private String icon;

    /** 描述 */
    @Excel(name = "描述")
    private String description;

    /** 排序(越小越靠前) */
    @Excel(name = "排序(越小越靠前)")
    private Long sortOrder;

    /** 状态(0正常 1停用) */
    @Excel(name = "状态(0正常 1停用)")
    private String status;

    /** 删除标志(0存在 1删除) */
    private String delFlag;

}
