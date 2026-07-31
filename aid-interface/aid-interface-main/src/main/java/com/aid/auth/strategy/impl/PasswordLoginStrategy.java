package com.aid.auth.strategy.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.auth.domain.dto.LoginRequest;
import com.aid.auth.strategy.LoginStrategy;
import com.aid.common.constant.Constants;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.exception.user.UserPasswordNotMatchException;
import com.aid.common.utils.MessageUtils;
import com.aid.framework.manager.AsyncManager;
import com.aid.framework.manager.factory.AsyncFactory;
import com.aid.framework.security.context.AuthenticationContextHolder;
import com.aid.framework.web.service.SysLoginService;
import com.aid.common.core.service.TokenService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 账号密码登录策略
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class PasswordLoginStrategy implements LoginStrategy {

    @Resource
    private AuthenticationManager authenticationManager;

    @Resource
    private TokenService tokenService;

    @Resource
    private SysLoginService sysLoginService;

    @Override
    public String getLoginType() {
        return "password";
    }

    @Override
    public void validate(LoginRequest request) {
        if (StrUtil.isBlank(request.getAccount())) {
            throw new ServiceException("账号不能为空");
        }
        if (StrUtil.isBlank(request.getPassword())) {
            throw new ServiceException("密码不能为空");
        }
        // 人机校验由 @CaptchaRequired + CaptchaInterceptor（行为验证码）统一前置完成，此处不做图形验证码校验
    }

    @Override
    public LoginUser login(LoginRequest request) {
        // 密码登录失败锁定校验（与 B 端 /login 共用同一份 redis 计数器）
        sysLoginService.assertAccountNotLocked(request.getAccount());
        // 登录前置校验
        sysLoginService.loginPreCheck(request.getAccount(), request.getPassword());

        Authentication authentication;
        try {

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    request.getAccount(), request.getPassword());
            // 设置认证上下文（供SysPasswordService使用）
            AuthenticationContextHolder.setContext(authToken);
            // 调用UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authToken);
        } catch (Exception e) {
            log.error("账号密码登录失败: account={}", request.getAccount(), e);
            if (e instanceof BadCredentialsException) {
                // 记录失败次数 + 达到阈值则锁定
                sysLoginService.recordLoginFailure(request.getAccount());
                // 记录登录失败日志
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                        request.getAccount(), Constants.LOGIN_FAIL,
                        MessageUtils.message("user.password.not.match")));
                throw new UserPasswordNotMatchException();
            }
            // 业务异常（账号锁定/停用等已美化文案）原样透传；其它认证异常统一短文案，防内部信息泄漏
            if (e instanceof ServiceException) {
                throw (ServiceException) e;
            }
            if (e instanceof DisabledException || e instanceof LockedException) {
                throw new ServiceException("账号已停用");
            }
            throw new ServiceException("登录失败");
        } finally {
            // 清除认证上下文
            AuthenticationContextHolder.clearContext();
        }

        // 登录成功清除失败计数
        sysLoginService.clearLoginFailure(request.getAccount());

        AsyncManager.me().execute(AsyncFactory.recordLogininfor(
                request.getAccount(), Constants.LOGIN_SUCCESS,
                MessageUtils.message("user.login.success")));

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        sysLoginService.recordLoginInfo(loginUser.getUserId());

        return loginUser;
    }
}
