package com.aid.aid.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

/**
 * C端「返佣明细」联表查询行 DTO
 * aid_invite_rebate_record LEFT JOIN sys_user，供 C 端分页展示，由 promotion 服务转换为出参 VO。
 *
 * @author 视觉AID
 */
@Data
public class InviteRebateRowDTO
{
    /** 记录ID */
    private Long id;

    /** 被邀请人用户ID（充值方） */
    private Long inviteeUserId;

    /** 被邀请人昵称 */
    private String nickName;

    /** 订单到账积分（返佣计算基数） */
    private BigDecimal orderCredits;

    /** 返佣比例(%)（发放时快照） */
    private BigDecimal rebateRatio;

    /** 实际返佣积分 */
    private BigDecimal rebateAmount;

    /** 状态（granted已发放 revoked已撤回） */
    private String status;

    /** 返佣发放时间 */
    private Date createTime;
}
