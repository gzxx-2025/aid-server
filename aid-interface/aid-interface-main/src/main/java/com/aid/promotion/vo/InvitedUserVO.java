package com.aid.promotion.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Data;

/**
 * 我邀请的用户列表项出参
 *
 * @author 视觉AID
 */
@Data
public class InvitedUserVO
{
    /** 被邀请人昵称 */
    private String nickName;

    /** 被邀请人头像（出参拼域名） */
    @MediaUrl
    private String avatar;

    /** 该用户累计带来的返佣积分 */
    private BigDecimal totalRebate;

    /** 注册时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date registerTime;
}
