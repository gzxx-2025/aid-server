package com.aid.common.aid.real.core;

import com.aid.common.aid.real.entity.RealAuthResult;

/**
 * 实名认证服务接口
 *
 * @author 视觉AID
 */
public interface RealAuthService {

    /**
     * 执行实名认证
     *
     * @param realName 真实姓名
     * @param idCard   身份证号
     * @param phone    手机号（三要素时需要）
     * @return 认证结果
     */
    RealAuthResult verify(String realName, String idCard, String phone);

    /**
     * 获取认证类型
     *
     * @return twoFactor / threeFactor
     */
    String getAuthType();
}
