package com.aid.common.aid.sms.config.properties;

import lombok.Data;

/**
 * SMS短信 配置属性
 *
 * 配置从数据库动态获取，不再绑定配置文件
 *
 * @author 视觉AID
 */
@Data
public class SmsProperties {

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 配置节点
     * 阿里云 dysmsapi.aliyuncs.com
     * 腾讯云 sms.tencentcloudapi.com
     */
    private String endpoint;

    /**
     * key
     */
    private String accessKeyId;

    /**
     * 密匙
     */
    private String accessKeySecret;

    /**
     * 短信签名
     */
    private String signName;

    /**
     * 短信应用ID (腾讯专属)
     */
    private String sdkAppId;

}
