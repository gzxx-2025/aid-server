package com.aid.common.aid.real.properties;

import lombok.Data;

/**
 * 实名认证配置属性
 *
 * @author 视觉AID
 */
@Data
public class RealAuthProperties {

    /**
     * 是否启用
     */
    private Boolean enabled = false;

    /**
     * 认证类型: twoFactor(二要素) / threeFactor(三要素)
     * 二要素：姓名+身份证号（不需要绑定手机号）
     * 三要素：姓名+身份证号+手机号（需要绑定手机号）
     */
    private String authType = "twoFactor";

    /**
     * 阿里云云市场 AppCode（二要素和三要素通用）
     */
    private String appCode;

    /**
     * 是否需要手机号
     */
    public boolean needPhone() {
        return "threeFactor".equals(authType);
    }
}
