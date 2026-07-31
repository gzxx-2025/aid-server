package com.aid.user.service.impl;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.common.exception.ServiceException;
import com.aid.user.service.IUserBalanceService;
import com.aid.user.vo.UserBalanceVO;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * C 端用户账户积分 Service 实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserBalanceServiceImpl implements IUserBalanceService {

    @Resource
    private IAidUserProfileService aidUserProfileService;

    /**
     * 查询当前用户账户积分，账户扩展信息未初始化时统一返回 0。
     *
     * @param userId 当前登录用户 ID
     * @return 账户积分信息
     */
    @Override
    public UserBalanceVO getBalance(Long userId) {
        if (Objects.isNull(userId)) {
            log.error("查询账户积分失败：登录用户为空");
            throw new ServiceException("登录已失效");
        }

        AidUserProfile profile = aidUserProfileService.getAccountBalanceByUserId(userId);
        if (Objects.isNull(profile)) {
            log.info("用户账户扩展信息未初始化，按零积分返回: userId={}", userId);
            return buildZeroBalance();
        }

        return UserBalanceVO.builder()
                .balance(defaultZero(profile.getBalance()))
                .frozenBalance(defaultZero(profile.getFrozenBalance()))
                .totalRecharge(defaultZero(profile.getTotalRecharge()))
                .totalConsumption(defaultZero(profile.getTotalConsumption()))
                .build();
    }

    /**
     * 构建零积分账户，保证前端收到的金额字段均非 null。
     */
    private UserBalanceVO buildZeroBalance() {
        return UserBalanceVO.builder()
                .balance(BigDecimal.ZERO)
                .frozenBalance(BigDecimal.ZERO)
                .totalRecharge(BigDecimal.ZERO)
                .totalConsumption(BigDecimal.ZERO)
                .build();
    }

    /**
     * 数据库历史空值统一转换为零。
     */
    private BigDecimal defaultZero(BigDecimal amount) {
        return Objects.isNull(amount) ? BigDecimal.ZERO : amount;
    }
}
