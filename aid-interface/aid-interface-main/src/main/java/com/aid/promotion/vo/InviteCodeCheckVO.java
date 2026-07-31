package com.aid.promotion.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Data;

/**
 * 邀请码预校验出参（注册页展示"您正在接受 XX 的邀请"）
 *
 * @author 视觉AID
 */
@Data
public class InviteCodeCheckVO
{
    /** 邀请码是否有效 */
    private boolean valid;

    /** 无效原因（valid=false 时返回，如"邀请活动未开启"、"邀请码无效"） */
    private String reason;

    /** 邀请人昵称（valid=true 时返回） */
    private String inviterNickName;

    /** 邀请人头像（valid=true 时返回，出参拼域名） */
    @MediaUrl
    private String inviterAvatar;
}
