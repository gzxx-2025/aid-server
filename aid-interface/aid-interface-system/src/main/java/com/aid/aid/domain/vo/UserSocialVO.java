package com.aid.aid.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户第三方账号信息响应对象
 *
 * @author 视觉AID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSocialVO {

    /**
     * 平台类型
     */
    private String platformSource;

    /**
     * 三方平台唯一标识
     */
    private String openid;

    /**
     * 三方平台全平台唯一标识 (如微信的unionid)
     */
    private String unionid;
}
