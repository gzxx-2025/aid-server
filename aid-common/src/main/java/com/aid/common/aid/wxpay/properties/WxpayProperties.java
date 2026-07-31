package com.aid.common.aid.wxpay.properties;

import lombok.Data;

/**
 * WeChat Pay V3 configuration properties.
 *
 * @author 视觉AID
 */
@Data
public class WxpayProperties {

    /**
     * Whether WeChat Pay is enabled.
     */
    private Boolean enabled = false;

    /**
     * AppId of mini program or official account.
     */
    private String appId;

    /**
     * Merchant id.
     */
    private String mchId;

    /**
     * API v3 key.
     */
    private String apiV3Key;

    /**
     * Merchant private key from apiclient_key.pem.
     */
    private String privateKey;

    /**
     * Merchant certificate serial number from apiclient_cert.pem.
     */
    private String serialNo;

    /**
     * WeChat Pay public key id.
     */
    private String publicKeyId;

    /**
     * WeChat Pay public key content.
     */
    private String publicKey;

    /**
     * Payment notification URL.
     */
    private String notifyUrl;
}
