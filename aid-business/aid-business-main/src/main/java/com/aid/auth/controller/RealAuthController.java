package com.aid.auth.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.common.aid.real.core.RealAuthTemplateFactory;
import com.aid.common.aid.real.entity.RealAuthRequest;
import com.aid.common.aid.real.entity.RealAuthResult;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.core.service.TokenService;
import com.aid.core.service.ISysUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户实名认证Controller
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "实名认证", description = "C端实名认证接口")
@RestController
@RequestMapping("/realAuth")
@RequiredArgsConstructor
public class RealAuthController {

    private final RealAuthTemplateFactory realAuthTemplateFactory;
    private final ISysUserService sysUserService;
    private final IAidUserProfileService aidUserProfileService;
    private final TokenService tokenService;
    private final RedisCache redisCache;

    /**
     * 实名认证冷却时间（秒）
     */
    private static final Integer AUTH_COOLDOWN_SECONDS = 120;

    private static final String AUTH_COOLDOWN_KEY = "real_auth:cooldown:";

    /**
     * 用户实名认证
     *
     * @param request 实名认证请求
     * @return 认证结果
     */
    @PostMapping("/verify")
    public AjaxResult verify(@Valid @RequestBody RealAuthRequest request) {
        Long userId = SecurityUtils.getUserId();

        log.info("用户实名认证请求: userId={}", userId);

        try {
            if (!realAuthTemplateFactory.isEnabled()) {
                return AjaxResult.error("实名认证功能未启用");
            }

            // SETNX 抢到冷却锁即视为拿到唯一执行权：TTL 内并发/重试请求直接返回，
            // 不会重复进入第三方认证造成双扣费（第三方抛异常也不提前释放锁）
            String cooldownKey = AUTH_COOLDOWN_KEY + userId;
            boolean acquired = Boolean.TRUE.equals(
                    redisCache.redisTemplate.opsForValue()
                            .setIfAbsent(cooldownKey, "1", AUTH_COOLDOWN_SECONDS, TimeUnit.SECONDS));
            if (!acquired) {
                long ttl = redisCache.getExpire(cooldownKey);
                long remain = ttl > 0 ? ttl : AUTH_COOLDOWN_SECONDS;
                log.info("实名认证冷却期内重复请求, userId={}, remain={}s", userId, remain);
                return AjaxResult.error("请" + remain + "秒后重试");
            }

            SysUser user = sysUserService.selectUserById(userId);
            if (user == null) {
                return AjaxResult.error("用户不存在");
            }

            String authType = realAuthTemplateFactory.getAuthType();
            String phone = null;

            if ("threeFactor".equals(authType)) {
                // 三要素认证需要手机号
                if (user.getPhonenumber() == null || user.getPhonenumber().isEmpty()) {
                    return AjaxResult.error("三要素认证需要先绑定手机号");
                }
                phone = user.getPhonenumber();
            }

            AidUserProfile existProfile = getUserProfileByUserId(userId);
            if (existProfile != null && "1".equals(existProfile.getIsReal())) {
                return AjaxResult.error("您已完成实名认证，无需重复认证");
            }

            RealAuthResult result = realAuthTemplateFactory.verify(
                    request.getRealName(),
                    request.getIdCard(),
                    phone
            );

            if (result.getSuccess()) {
                updateUserRealAuthInfo(userId, request.getRealName(), request.getIdCard());

                return AjaxResult.success("实名认证通过", result);
            } else {
                // 第三方原始 message 只入日志，前端返回固定短文案（防第三方内部信息透传）
                log.info("实名认证未通过, userId={}, thirdPartyMessage={}", userId, result.getMessage());
                return AjaxResult.error("实名认证未通过");
            }
        } catch (Exception e) {
            log.error("实名认证异常: {}", e.getMessage(), e);
            return AjaxResult.error("实名认证失败");
        }
    }

    /**
     * 获取实名认证状态
     *
     * @return 认证状态
     */
    @PostMapping("/status")
    public AjaxResult getStatus() {
        Long userId = SecurityUtils.getUserId();

        Map<String, Object> data = new HashMap<>();

        SysUser user = sysUserService.selectUserById(userId);
        data.put("hasPhone", user != null && user.getPhonenumber() != null && !user.getPhonenumber().isEmpty());

        data.put("enabled", realAuthTemplateFactory.isEnabled());
        data.put("authType", realAuthTemplateFactory.getAuthType());
        data.put("needPhone", realAuthTemplateFactory.needPhone());

        AidUserProfile profile = getUserProfileByUserId(userId);
        if (profile != null && "1".equals(profile.getIsReal())) {
            data.put("isReal", true);
            data.put("realName", maskName(profile.getRealName()));
            data.put("idCard", maskIdCard(profile.getIdCard()));
        } else {
            data.put("isReal", false);
            data.put("realName", null);
            data.put("idCard", null);
        }

        return AjaxResult.success(data);
    }

    /**
     * 根据用户ID获取用户扩展信息
     */
    private AidUserProfile getUserProfileByUserId(Long userId) {
        return aidUserProfileService.getByUserId(userId);
    }

    /**
     * 更新用户实名认证信息
     */
    private void updateUserRealAuthInfo(Long userId, String realName, String idCard) {
        AidUserProfile profile = getUserProfileByUserId(userId);

        if (profile == null) {
            profile = new AidUserProfile();
            profile.setUserId(userId);
            profile.setBalance(BigDecimal.ZERO);
            profile.setFrozenBalance(BigDecimal.ZERO);
            profile.setMemberLevel("normal");
            profile.setTotalRecharge(BigDecimal.ZERO);
            profile.setTotalConsumption(BigDecimal.ZERO);
            profile.setIsReal("1");
            profile.setRealName(realName);
            profile.setIdCard(idCard);
            profile.setCreateTime(DateUtils.getNowDate());
            aidUserProfileService.save(profile);
        } else {
            // 仅更新实名相关字段，避免把旧的 balance/frozenBalance/totalConsumption/totalRecharge 回写覆盖
            // 同时绕开账户行 updateById，彻底避免与 IAccountUpdateService 并发打同一行造成锁等待
            LambdaUpdateWrapper<AidUserProfile> update = Wrappers.lambdaUpdate();
            update.eq(AidUserProfile::getId, profile.getId());
            update.set(AidUserProfile::getIsReal, "1");
            update.set(AidUserProfile::getRealName, realName);
            update.set(AidUserProfile::getIdCard, idCard);
            update.set(AidUserProfile::getUpdateTime, DateUtils.getNowDate());
            aidUserProfileService.update(update);
        }

        log.info("用户实名认证信息已更新: userId={}", userId);
    }


    /**
     * 姓名脱敏
     */
    private String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    /**
     * 身份证脱敏
     */
    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() <= 6) {
            return idCard;
        }
        return idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length() - 4);
    }
}
