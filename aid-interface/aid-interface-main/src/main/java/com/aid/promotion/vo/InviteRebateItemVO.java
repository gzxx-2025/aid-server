package com.aid.promotion.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 返佣明细列表项出参
 *
 * @author 视觉AID
 */
@Data
public class InviteRebateItemVO
{
    /** 被邀请人昵称（充值方） */
    private String nickName;

    /** 订单到账积分（返佣计算基数） */
    private BigDecimal orderCredits;

    /** 返佣比例(%)（发放时快照） */
    private BigDecimal rebateRatio;

    /** 实际返佣积分 */
    private BigDecimal rebateAmount;

    /** 状态（granted已发放 revoked已撤回） */
    private String status;

    /** 状态名称（已发放/已撤回） */
    private String statusName;

    /** 返佣时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
