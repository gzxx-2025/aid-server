package com.aid.aid.domain.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

/**
 * C端「我邀请的用户」联表查询行 DTO
 * aid_invite_relation LEFT JOIN sys_user，供 C 端分页展示，由 promotion 服务转换为出参 VO。
 *
 * @author 视觉AID
 */
@Data
public class InvitedUserRowDTO
{
    /** 被邀请人用户ID */
    private Long inviteeUserId;

    /** 被邀请人昵称 */
    private String nickName;

    /** 被邀请人头像（相对路径，出参层拼域名） */
    private String avatar;

    /** 关系状态（0正常 1禁用） */
    private String status;

    /** 该用户累计带来的返佣积分 */
    private BigDecimal totalRebate;

    /** 注册时间（关系绑定时间） */
    private Date registerTime;
}
