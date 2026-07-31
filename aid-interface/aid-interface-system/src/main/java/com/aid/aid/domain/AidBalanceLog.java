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
 * 余额变动记录对象 aid_balance_log
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_balance_log")
public class AidBalanceLog extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 变动类型 (recharge充值/consume消费/refund退款/freeze冻结/unfreeze解冻) */
    @Excel(name = "变动类型 (recharge充值/consume消费/refund退款/freeze冻结/unfreeze解冻)")
    private String changeType;

    /** 变动金额 (正数增加, 负数减少) */
    @Excel(name = "变动金额 (正数增加, 负数减少)")
    private BigDecimal amount;

    /** 变动前余额 */
    @Excel(name = "变动前余额")
    private BigDecimal beforeBalance;

    /** 变动后余额 */
    @Excel(name = "变动后余额")
    private BigDecimal afterBalance;

    /** 关联业务ID (订单号/充值流水号等) */
    @Excel(name = "关联业务ID (订单号/充值流水号等)")
    private String relatedId;

    /** 业务类型 (chat对话/create创作/recharge充值/refund退款等) */
    @Excel(name = "业务类型 (chat对话/create创作/recharge充值/refund退款等)")
    private String bizType;

    /** 业务名称 (对话消耗/创作消耗/余额充值等) */
    @Excel(name = "业务名称 (对话消耗/创作消耗/余额充值等)")
    private String bizName;

    /** 本次业务实际使用的模型编码，多个模型用英文逗号分隔 */
    private String modelCode;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
