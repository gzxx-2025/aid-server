package com.aid.promotion.service.impl;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aid.billing.service.IAccountUpdateService;
import com.aid.promotion.constant.PromotionConstants;
import com.aid.promotion.domain.RegisterBonusConfig;
import com.aid.promotion.service.IPromotionConfigService;
import com.aid.promotion.service.IRegisterBonusService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 注册送积分Service实现
 * 注册链路营销钩子：配置判定 → 事务提交后入账（reward，幂等），任何异常只记日志不上抛。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterBonusServiceImpl implements IRegisterBonusService
{
    private final IPromotionConfigService promotionConfigService;

    private final IAccountUpdateService accountUpdateService;

    @Override
    public void grantAfterRegister(Long userId, String channel)
    {
        try
        {
            // 用户ID缺失直接跳过（防御，正常注册链路不会发生）
            if (Objects.isNull(userId))
            {
                return;
            }
            RegisterBonusConfig config = promotionConfigService.getRegisterBonusConfig();
            // 总开关关闭不发放
            if (!config.isEnabled())
            {
                return;
            }
            // 该注册渠道未参与活动不发放（如邮箱注册防薅羊毛可单独关闭）
            if (!config.isChannelEnabled(channel))
            {
                log.info("注册渠道未参与送积分活动, userId={}, channel={}", userId, channel);
                return;
            }
            BigDecimal amount = config.getAmount();
            // 赠送数量未配置或为 0 不发放
            if (Objects.isNull(amount) || amount.signum() <= 0)
            {
                return;
            }

            if (TransactionSynchronizationManager.isSynchronizationActive())
            {
                // 注册事务尚未提交：挂到提交后发放，保证 aid_user_profile 已落库可见；注册回滚则不发放
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        doGrant(userId, channel, amount);
                    }
                });
            }
            else
            {
                // 无事务上下文（防御分支）：直接发放
                doGrant(userId, channel, amount);
            }
        }
        catch (Exception e)
        {
            // 营销奖励失败绝不阻断注册主流程
            log.error("注册送积分处理异常, userId={}, channel={}", userId, channel, e);
        }
    }

    /**
     * 实际发放：走统一 reward 入账（不计累计充值），traceId 一人一次幂等
     */
    private void doGrant(Long userId, String channel, BigDecimal amount)
    {
        try
        {
            String traceId = PromotionConstants.TRACE_PREFIX_REGISTER_BONUS + userId;
            accountUpdateService.reward(userId, amount,
                    traceId, PromotionConstants.BIZ_TYPE_REGISTER_BONUS, PromotionConstants.BIZ_NAME_REGISTER_BONUS);
            log.info("注册送积分发放成功, userId={}, channel={}, amount={}", userId, channel, amount);
        }
        catch (Exception e)
        {
            // afterCommit 阶段异常无法回滚注册，只记录待人工核查
            log.error("注册送积分发放失败, userId={}, channel={}, amount={}", userId, channel, amount, e);
        }
    }
}
