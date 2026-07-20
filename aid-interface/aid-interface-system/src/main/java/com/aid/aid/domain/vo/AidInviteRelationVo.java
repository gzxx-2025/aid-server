package com.aid.aid.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 邀请关系列表VO（后台管理）
 * 聚合 aid_invite_relation 与 sys_user（邀请人/被邀请人昵称、账号），
 * 同时复用作为列表查询条件载体（邀请人ID/被邀请人ID/邀请码/状态）。
 *
 * @author 视觉AID
 */
@Data
public class AidInviteRelationVo
{
    /** 主键ID */
    private Long id;

    /** 邀请人用户ID */
    private Long inviterUserId;

    /** 邀请人昵称 (sys_user.nick_name) */
    private String inviterNickName;

    /** 邀请人账号 (sys_user.user_name) */
    private String inviterUserName;

    /** 被邀请人用户ID */
    private Long inviteeUserId;

    /** 被邀请人昵称 (sys_user.nick_name) */
    private String inviteeNickName;

    /** 被邀请人账号 (sys_user.user_name) */
    private String inviteeUserName;

    /** 注册时使用的邀请码 */
    private String inviteCode;

    /** 被邀请人注册渠道（sms手机号/email邮箱/wechat微信） */
    private String registerChannel;

    /** 被邀请人注册IP */
    private String registerIp;

    /** 关系状态（0正常 1禁用） */
    private String status;

    /** 该关系累计返佣积分 */
    private BigDecimal totalRebate;

    /** 备注 */
    private String remark;

    /** 绑定时间（即被邀请人注册时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
