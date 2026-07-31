package com.aid.aid.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应对象
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /**
     * 访问令牌
     */
    private String token;

    /**
     * 用户信息
     */
    private UserInfoVO userInfo;

    /**
     * 已绑定的第三方平台列表
     */
    private List<UserSocialVO> social;
}
