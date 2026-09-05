package com.aid.aid.service;

/**
 * 账号注销后再次注册限制服务。
 *
 * @author 视觉AID
 */
public interface IAccountCancellationService {

    void recordCancellation(Long userId, String identityType, String identity);

    void checkRegistrationAllowed(String identityType, String identity);

    boolean isRestrictionEnabled();

    int getRestrictionDays();
}
