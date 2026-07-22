package com.aid.common.aid.wxlogin.properties;

import lombok.Data;

/**
 * 微信公众号登录配置属性
 *
 * @author 视觉AID
 */
@Data
public class WxLoginProperties {

    /**
     * 是否启用微信登录
     */
    private Boolean enabled;

    /**
     * 微信公众号 AppId
     */
    private String appId;

    /**
     * 微信公众号 Secret
     */
    private String secret;

    /**
     * 微信公众号 Token（用于消息验证）
     */
    private String token;

    /**
     * WeChat EncodingAESKey for encrypted message callbacks.
     */
    private String encodingAesKey;

    /**
     * 用户关注公众号后自动回复的文本内容
     */
    private String subscribeReplyContent;

    /**
     * 二维码有效期（秒）
     */
    private Integer qrcodeExpireSeconds = 300;
}
