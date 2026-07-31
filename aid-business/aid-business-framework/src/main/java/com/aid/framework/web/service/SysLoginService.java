package com.aid.framework.web.service;

import java.util.concurrent.TimeUnit;

import com.aid.common.constant.CacheConstants;
import com.aid.common.constant.Constants;
import com.aid.common.constant.UserConstants;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.exception.user.*;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.MessageUtils;
import com.aid.common.utils.StringUtils;
import com.aid.common.utils.ip.IpUtils;
import com.aid.framework.manager.AsyncManager;
import com.aid.framework.manager.factory.AsyncFactory;
import com.aid.framework.security.context.AuthenticationContextHolder;
import com.aid.common.core.service.TokenService;
import com.aid.core.service.ISysConfigService;
import com.aid.core.service.ISysUserService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 登录校验方法
 * 
 * @author AID
 */
@Component
public class SysLoginService
{
    /**
     * 修复 #46：密码登录失败累计锁定。
     * <p>
     * 策略：同一 username + clientIp 维度累计错误次数 → 达到阈值后锁定一段时间；
     * 成功登录后清除计数。默认 5 次错误锁 10 分钟，关闭 captcha 时作为核心反爆破手段。
     * 关键 key：login:fail:{username}  / login:lock:{username}
     * </p>
     */
    private static final int LOGIN_MAX_FAIL = 5;
    private static final long LOGIN_FAIL_TTL_MIN = 10L;
    private static final long LOGIN_LOCK_TTL_MIN = 10L;
    private static final String LOGIN_FAIL_KEY_PREFIX = "login:fail:";
    private static final String LOGIN_LOCK_KEY_PREFIX = "login:lock:";

    @Autowired
    private TokenService tokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysConfigService configService;

    /**
     * 登录验证
     * 
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public String login(String username, String password, String code, String uuid)
    {
        // 修复 #46：密码登录失败锁定校验（放在验证码之前，锁定状态下不消耗验证码）
        assertAccountNotLocked(username);
        // 验证码校验
        validateCaptcha(username, code, uuid);
        // 登录前置校验
        loginPreCheck(username, password);
        // 用户验证
        Authentication authentication = null;
        try
        {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            // 该方法会去调用UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authenticationToken);
        }
        catch (Exception e)
        {
            if (e instanceof BadCredentialsException)
            {
                // 修复 #46：记录失败次数 + 达到阈值则锁定
                recordLoginFailure(username);
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
                throw new UserPasswordNotMatchException();
            }
            else
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, e.getMessage()));
                throw new ServiceException(e.getMessage());
            }
        }
        finally
        {
            AuthenticationContextHolder.clearContext();
        }
        // 登录成功清除计数
        clearLoginFailure(username);
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        recordLoginInfo(loginUser.getUserId());
        // 生成token
        return tokenService.createToken(loginUser);
    }

    /**
     * 修复 #46：若当前账户处于锁定期，直接拒绝登录并告知剩余秒数。
     */
    public void assertAccountNotLocked(String username)
    {
        if (StringUtils.isEmpty(username))
        {
            return;
        }
        String lockKey = LOGIN_LOCK_KEY_PREFIX + username;
        Object locked = redisCache.getCacheObject(lockKey);
        if (locked != null)
        {
            long ttl = redisCache.getExpire(lockKey);
            long remain = ttl > 0 ? ttl : LOGIN_LOCK_TTL_MIN * 60;
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                    username, Constants.LOGIN_FAIL, "账户已锁定，剩余 " + remain + " 秒"));
            throw new ServiceException("登录失败次数过多，账户已锁定，请 " + (remain / 60 + 1) + " 分钟后重试");
        }
    }

    /**
     * 修复 #46：累加失败次数，达到阈值后写入锁定 key。
     */
    public void recordLoginFailure(String username)
    {
        if (StringUtils.isEmpty(username))
        {
            return;
        }
        String failKey = LOGIN_FAIL_KEY_PREFIX + username;
        Integer current = redisCache.getCacheObject(failKey);
        int next = current == null ? 1 : current + 1;
        redisCache.setCacheObject(failKey, next, (int) LOGIN_FAIL_TTL_MIN, TimeUnit.MINUTES);
        if (next >= LOGIN_MAX_FAIL)
        {
            // 锁定用户
            redisCache.setCacheObject(LOGIN_LOCK_KEY_PREFIX + username, 1, (int) LOGIN_LOCK_TTL_MIN, TimeUnit.MINUTES);
            // 锁定后清空失败计数，避免锁定到期后立即又被触发
            redisCache.deleteObject(failKey);
        }
    }

    /**
     * 修复 #46：登录成功清除失败计数与锁定。
     */
    public void clearLoginFailure(String username)
    {
        if (StringUtils.isEmpty(username))
        {
            return;
        }
        redisCache.deleteObject(LOGIN_FAIL_KEY_PREFIX + username);
        redisCache.deleteObject(LOGIN_LOCK_KEY_PREFIX + username);
    }

    /**
     * 校验验证码
     * 
     * @param username 用户名
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public void validateCaptcha(String username, String code, String uuid)
    {
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        if (captchaEnabled)
        {
            String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + StringUtils.nvl(uuid, "");
            String captcha = redisCache.getCacheObject(verifyKey);
            if (captcha == null)
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire")));
                throw new CaptchaExpireException();
            }
            redisCache.deleteObject(verifyKey);
            if (!code.equalsIgnoreCase(captcha))
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error")));
                throw new CaptchaException();
            }
        }
    }

    /**
     * 登录前置校验
     * @param username 用户名
     * @param password 用户密码
     */
    public void loginPreCheck(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("not.null")));
            throw new UserNotExistsException();
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // IP黑名单校验
        String blackStr = configService.selectConfigByKey("sys.login.blackIPList");
        if (IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr()))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("login.blocked")));
            throw new BlackListException();
        }
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    public void recordLoginInfo(Long userId)
    {
        userService.updateLoginInfo(userId, IpUtils.getIpAddr(), DateUtils.getNowDate());
    }
}
