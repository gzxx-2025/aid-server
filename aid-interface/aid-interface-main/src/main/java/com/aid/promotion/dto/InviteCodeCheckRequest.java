package com.aid.promotion.dto;

import java.io.Serial;
import java.io.Serializable;

import lombok.Data;

/**
 * 邀请码预校验入参（匿名接口，注册页输入邀请码时预检）
 *
 * @author 视觉AID
 */
@Data
public class InviteCodeCheckRequest implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 邀请码（8位，大小写不敏感） */
    private String inviteCode;
}
