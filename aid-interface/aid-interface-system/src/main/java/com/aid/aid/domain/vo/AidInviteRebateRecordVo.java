package com.aid.aid.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 邀请返佣记录列表VO（后台管理）
 * 聚合 aid_invite_rebate_record 与 sys_user（邀请人/被邀请人昵称），
 * 同时复用作为列表查询条件载体（邀请人ID/被邀请人ID/订单号/状态）。
 *
 * @author 视觉AID
 */
@Data
public class AidInviteRebateRecordVo
{
    /** 主键ID */
    private Long id;

    /** 邀请人用户ID（返佣接收方） */
    private Long inviterUserId;

    /** 邀请人昵称 (sys_user.nick_name) */
    private String inviterNickName;

    /** 被邀请人用户ID（充值方） */
    private Long inviteeUserId;

    /** 被邀请人昵称 (sys_user.nick_name) */
    private String inviteeNickName;

    /** 充值订单号 */
    private String orderNo;

    /** 订单到账积分（返佣计算基数） */
    private BigDecimal orderCredits;

    /** 订单实付金额(元) */
    private BigDecimal payPrice;

    /** 返佣比例(%)（发放时快照） */
    private BigDecimal rebateRatio;

    /** 实际返佣积分 */
    private BigDecimal rebateAmount;

    /** 状态（granted已发放 revoked已撤回-订单退款扣回） */
    private String status;

    /** 备注 */
    private String remark;

    /** 返佣发放时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
