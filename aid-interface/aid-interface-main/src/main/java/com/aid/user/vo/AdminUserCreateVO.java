package com.aid.user.vo;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

/**
 * 后台创建 C 端用户结果。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class AdminUserCreateVO {

    /** 用户 ID。 */
    private Long userId;

    /** 可用于密码登录的手机号或邮箱。 */
    private String account;

    /** 账号类型：phone 或 email。 */
    private String accountType;

    /** 仅在创建成功响应中返回一次的初始密码。 */
    @ToString.Exclude
    private String password;
}
