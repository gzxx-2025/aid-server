package com.aid.promotion.service;

/**
 * 注册送积分Service接口
 *
 * @author 视觉AID
 */
public interface IRegisterBonusService
{
    /**
     * 注册成功后发放注册赠送积分（静默处理，绝不抛异常阻断注册主流程）。
     * 在注册事务内调用：奖励发放挂到事务提交后执行（afterCommit），
     * 保证账户档案已落库可见；注册回滚时奖励自然不发放。
     * 幂等：traceId = register_bonus_{userId}，同一用户只发一次。
     *
     * @param userId  新注册用户ID
     * @param channel 注册渠道（sms手机号/email邮箱/wechat微信）
     */
    void grantAfterRegister(Long userId, String channel);
}
