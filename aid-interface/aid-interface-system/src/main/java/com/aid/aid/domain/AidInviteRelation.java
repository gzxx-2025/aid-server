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
 * 邀请关系对象 aid_invite_relation
 * 一个用户只能被邀请一次（invitee_user_id 全局唯一），仅注册瞬间建立，禁止注册后补绑。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_invite_relation")
public class AidInviteRelation extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 邀请人用户ID (关联sys_user.user_id) */
    private Long inviterUserId;

    /** 被邀请人用户ID（全局唯一，一个用户只能被邀请一次） */
    private Long inviteeUserId;

    /** 注册时使用的邀请码（快照） */
    private String inviteCode;

    /** 被邀请人注册渠道（sms手机号/email邮箱/wechat微信） */
    private String registerChannel;

    /** 被邀请人注册IP（风控审计） */
    private String registerIp;

    /** 关系状态（0正常 1禁用；禁用后该关系不再产生返佣） */
    private String status;

    /** 该关系累计返佣积分（冗余统计，退款扣回时同步扣减） */
    private BigDecimal totalRebate;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}
