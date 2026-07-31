package com.aid.aid.domain;

import java.io.Serializable;
import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 邀请充值返佣记录对象 aid_invite_rebate_record
 * order_no 全局唯一保证同一订单只返佣一次；订单退款时状态置为 revoked 并扣回积分。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_invite_rebate_record")
public class AidInviteRebateRecord extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 邀请人用户ID（返佣接收方） */
    private Long inviterUserId;

    /** 被邀请人用户ID（充值方） */
    private Long inviteeUserId;

    /** 充值订单号（全局唯一，返佣幂等键） */
    private String orderNo;

    /** 订单到账积分（返佣计算基数） */
    private BigDecimal orderCredits;

    /** 订单实付金额(元)（审计快照） */
    private BigDecimal payPrice;

    /** 返佣比例(%)（发放时快照） */
    private BigDecimal rebateRatio;

    /** 实际返佣积分 */
    private BigDecimal rebateAmount;

    /** 状态（granted已发放 revoked已撤回-订单退款扣回） */
    private String status;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}
