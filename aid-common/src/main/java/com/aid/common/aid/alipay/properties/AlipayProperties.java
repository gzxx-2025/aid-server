package com.aid.common.aid.alipay.properties;

import lombok.Data;

/**
 * 支付宝V3配置属性
 *
 * @author 视觉AID
 */
@Data
public class AlipayProperties {

    /**
     * 是否启用
     */
    private Boolean enabled = false;

    /**
     * 应用ID
     */
    private String appId;

    /**
     * 商户 PID（seller_id / 收款方账号ID）
     * 用于异步通知来源校验：确认通知中的 seller_id 确为本商户收款账户。
     * 选填——为空时跳过 seller_id 校验（app_id 校验始终生效）。
     */
    private String pid;

    /**
     * 应用私钥
     */
    private String privateKey;

    /**
     * 支付宝公钥
     */
    private String alipayPublicKey;

    /**
     * 异步通知地址
     */
    private String notifyUrl;

    /**
     * 同步返回地址
     */
    private String returnUrl;

    /**
     * 是否沙箱环境
     */
    private Boolean sandbox = false;

    /**
     * 签名类型（RSA2）
     */
    private String signType = "RSA2";

    /**
     * 字符集
     */
    private String charset = "UTF-8";

    /**
     * 返回格式
     */
    private String format = "json";
}
