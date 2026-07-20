package com.aid.auth.strategy.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.auth.domain.dto.LoginRequest;
import com.aid.auth.strategy.LoginStrategy;
import com.aid.auth.util.SilentRegistrationUtils;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.constant.AuthConstants;
import com.aid.common.constant.Constants;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.SecurityUtils;
import com.aid.common.utils.ip.IpUtils;
import com.aid.framework.manager.AsyncManager;
import com.aid.framework.manager.factory.AsyncFactory;
import com.aid.core.service.ISysConfigService;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.service.IInviteService;
import com.aid.promotion.service.IRegisterBonusService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 邮箱验证码登录策略
 * 支持自动注册，常量与公共方法统一抽到 {@link SilentRegistrationUtils}
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class EmailLoginStrategy implements LoginStrategy {

    @Resource
    private RedisCache redisCache;

    @Resource
    private ISysUserService sysUserService;

    @Resource
    private ISysConfigService sysConfigService;

    @Resource
    private IAidConfigService aidConfigService;

    @Resource
    private MediaUrlResolver mediaUrlResolver;

    @Resource
    private IInviteService inviteService;

    @Resource
    private IRegisterBonusService registerBonusService;

    @Override
    public String getLoginType() {
        return "email";
    }

    @Override
    public void validate(LoginRequest request) {
        if (StrUtil.isBlank(request.getAccount())) {
            throw new ServiceException("邮箱不能为空");
        }
        if (!request.getAccount().matches(AuthConstants.EMAIL_REGEX)) {
            throw new ServiceException("邮箱格式错误");
        }
        if (StrUtil.isBlank(request.getCode())) {
            throw new ServiceException("验证码不能为空");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginUser login(LoginRequest request) {
        String email = request.getAccount();
        String code = request.getCode();

        // 校验验证码是否正确，cache key 按 scene=login 隔离
        String cacheKey = AuthConstants.getCodeCacheKey(AuthConstants.SCENE_LOGIN, AuthConstants.BIND_TYPE_EMAIL, email);
        String cachedCode = redisCache.getCacheObject(cacheKey);
        if (Objects.isNull(cachedCode)) {
            log.info("邮箱验证码已过期: email={}", email);
            throw new ServiceException("验证码已过期");
        }
        if (!Objects.equals(cachedCode, code)) {
            // 验证码 code 不明文入日志
            log.info("邮箱验证码错误: email={}", email);
            throw new ServiceException("验证码错误");
        }

        // 验证通过后删除验证码
        redisCache.deleteObject(cacheKey);

        SysUser user = sysUserService.selectUserByEmail(email);

        if (Objects.isNull(user)) {
            // 用户不存在，自动注册
            user = createSysUser(email);
            log.info("邮箱登录自动注册成功: email={}, userId={}", email, user.getUserId());
            // 注册瞬间绑定邀请关系（静默，注册事务内，回滚即解除；老用户登录不会走到这里）
            inviteService.bindOnRegister(user.getUserId(), request.getInviteCode(), AuthConstants.BIND_TYPE_EMAIL);
            // 注册送积分（静默，事务提交后发放，幂等一人一次；邮箱渠道可在后台单独关闭防薅羊毛）
            registerBonusService.grantAfterRegister(user.getUserId(), AuthConstants.BIND_TYPE_EMAIL);
        } else {
            if (!"0".equals(user.getStatus())) {
                log.info("用户已停用: email={}", email);
                throw new ServiceException("账号已停用");
            }
        }

        AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                email, Constants.LOGIN_SUCCESS, "邮箱验证码登录成功"));

        sysUserService.updateLoginInfo(user.getUserId(), IpUtils.getIpAddr(), DateUtils.getNowDate());

        LoginUser loginUser = new LoginUser();
        loginUser.setUser(user);
        loginUser.setUserId(user.getUserId());

        return loginUser;
    }

    /**
     * 读取后台配置的默认头像列表（aid_config: default_avatar/urls，逗号分隔）。
     * 未配置或读取异常时返回空串，最终头像可为空。
     */
    private String resolveDefaultAvatarUrls() {
        try {
            return aidConfigService.getConfigValue("default_avatar", "urls");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 创建系统用户（静默注册）
     *
     * @param email 邮箱
     * @return 创建的用户
     */
    private SysUser createSysUser(String email) {
        // 入库前统一归一化为相对路径（剥掉本站 CDN/本地域名），禁止 sys_user.avatar 存全链接，读取层按域名拼接
        String randomAvatar = mediaUrlResolver.toRelativePath(
                SilentRegistrationUtils.pickRandomAvatar(resolveDefaultAvatarUrls()));

        SysUser sysUser = new SysUser();
        sysUser.setUserName(SilentRegistrationUtils.generateUserName());
        sysUser.setNickName(SilentRegistrationUtils.generateNickname());
        sysUser.setAvatar(randomAvatar);
        sysUser.setEmail(email);
        sysUser.setSex("2"); // 未知
        // 生成不可用的强随机密码
        sysUser.setPassword(SecurityUtils.encryptPassword(SilentRegistrationUtils.generateUnusablePassword()));
        sysUser.setStatus("0"); // 正常
        sysUser.setDelFlag("0"); // 未删除
        sysUser.setDeptId(SilentRegistrationUtils.DEFAULT_DEPT_ID);
        sysUser.setCreateBy("system");
        sysUser.setCreateTime(new Date());
        sysUser.setLoginIp(IpUtils.getIpAddr());
        sysUser.setLoginDate(new Date());

        sysUser.setRoleIds(new Long[]{SilentRegistrationUtils.DEFAULT_ROLE_ID});

        int rows = sysUserService.insertUser(sysUser);
        if (rows <= 0) {
            log.error("注册用户失败，邮箱: {}", email);
            throw new ServiceException("注册失败");
        }

        AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                email, Constants.LOGIN_SUCCESS, "邮箱登录自动注册"));

        log.info("静默注册成功，用户ID: {}, 邮箱: {}（已生成不可用随机密码，仅能通过验证码登录）",
                sysUser.getUserId(), email);

        return sysUser;
    }
}
