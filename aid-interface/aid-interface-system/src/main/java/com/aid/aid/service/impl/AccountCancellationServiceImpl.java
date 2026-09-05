package com.aid.aid.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.aid.aid.domain.AidAccountCancellation;
import com.aid.aid.mapper.AidAccountCancellationMapper;
import com.aid.aid.service.IAccountCancellationService;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.constant.AccountCancellationConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 账号注销后再次注册限制服务实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AccountCancellationServiceImpl
        extends ServiceImpl<AidAccountCancellationMapper, AidAccountCancellation>
        implements IAccountCancellationService {

    @Resource
    private IAidConfigService aidConfigService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordCancellation(Long userId, String identityType, String identity) {
        if (Objects.isNull(userId)) {
            log.error("注销身份记录缺少用户ID");
            throw new ServiceException("注销记录失败");
        }
        String identityHash = hashIdentity(identityType, identity);
        if (StrUtil.isBlank(identityHash)) {
            return;
        }

        // 仅查询更新所需字段，避免后续新增列时扩大读取范围。
        LambdaQueryWrapper<AidAccountCancellation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(AidAccountCancellation::getId)
                .eq(AidAccountCancellation::getIdentityType, identityType)
                .eq(AidAccountCancellation::getIdentityHash, identityHash)
                .last("LIMIT 1");
        AidAccountCancellation existing = getOne(queryWrapper, false);
        Date now = DateUtils.getNowDate();
        if (Objects.nonNull(existing)) {
            AidAccountCancellation update = new AidAccountCancellation();
            update.setId(existing.getId());
            update.setUserId(userId);
            update.setCancelledAt(now);
            update.setUpdateBy("system");
            update.setUpdateTime(now);
            if (!updateById(update)) {
                log.error("注销身份记录更新失败: userId={}, identityType={}", userId, identityType);
                throw new ServiceException("注销记录失败");
            }
            return;
        }

        AidAccountCancellation cancellation = new AidAccountCancellation();
        cancellation.setUserId(userId);
        cancellation.setIdentityType(identityType);
        cancellation.setIdentityHash(identityHash);
        cancellation.setCancelledAt(now);
        cancellation.setCreateBy("system");
        cancellation.setCreateTime(now);
        if (!save(cancellation)) {
            log.error("注销身份记录新增失败: userId={}, identityType={}", userId, identityType);
            throw new ServiceException("注销记录失败");
        }
    }

    @Override
    public void checkRegistrationAllowed(String identityType, String identity) {
        if (!isRestrictionEnabled()) {
            return;
        }
        String identityHash = hashIdentity(identityType, identity);
        if (StrUtil.isBlank(identityHash)) {
            return;
        }

        // 仅查询冷静期计算所需字段，避免读取无关审计信息。
        LambdaQueryWrapper<AidAccountCancellation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.select(AidAccountCancellation::getId, AidAccountCancellation::getCancelledAt)
                .eq(AidAccountCancellation::getIdentityType, identityType)
                .eq(AidAccountCancellation::getIdentityHash, identityHash)
                .last("LIMIT 1");
        AidAccountCancellation cancellation = getOne(queryWrapper, false);
        if (Objects.isNull(cancellation) || Objects.isNull(cancellation.getCancelledAt())) {
            return;
        }

        long dayMillis = TimeUnit.DAYS.toMillis(1);
        long blockedUntil = cancellation.getCancelledAt().getTime()
                + TimeUnit.DAYS.toMillis(getRestrictionDays());
        long remainingMillis = blockedUntil - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            return;
        }

        long remainingDays = Math.max(1L, (remainingMillis + dayMillis - 1L) / dayMillis);
        log.info("注销账号再次注册被拦截: identityType={}, remainingDays={}", identityType, remainingDays);
        throw new ServiceException(remainingDays + "天后可注册");
    }

    @Override
    public boolean isRestrictionEnabled() {
        String value = aidConfigService.getConfigValue(
                AccountCancellationConstants.CONFIG_CATEGORY,
                AccountCancellationConstants.CONFIG_ENABLED);
        if (StrUtil.isBlank(value)) {
            return AccountCancellationConstants.DEFAULT_ENABLED;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        log.error("注销再注册开关配置非法: value={}", value);
        return AccountCancellationConstants.DEFAULT_ENABLED;
    }

    @Override
    public int getRestrictionDays() {
        String value = aidConfigService.getConfigValue(
                AccountCancellationConstants.CONFIG_CATEGORY,
                AccountCancellationConstants.CONFIG_DAYS);
        if (StrUtil.isBlank(value)) {
            return AccountCancellationConstants.DEFAULT_DAYS;
        }
        try {
            int days = Integer.parseInt(value);
            if (days >= AccountCancellationConstants.MIN_DAYS
                    && days <= AccountCancellationConstants.MAX_DAYS) {
                return days;
            }
        } catch (NumberFormatException ignored) {
            // 非法值统一回退安全默认值。
        }
        log.error("注销再注册天数配置非法: value={}", value);
        return AccountCancellationConstants.DEFAULT_DAYS;
    }

    private String hashIdentity(String identityType, String identity) {
        if (StrUtil.isBlank(identityType) || StrUtil.isBlank(identity)) {
            return null;
        }
        String normalized = identity.trim();
        if (AccountCancellationConstants.IDENTITY_EMAIL.equals(identityType)) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return DigestUtil.sha256Hex(identityType + ":" + normalized);
    }
}
