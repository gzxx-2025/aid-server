package com.aid.common.satoken.utils;

import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.utils.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录态帮助类（与源系统 LoginHelper 常用方法对齐；底层适配 Spring Security）
 */
public final class LoginHelper {

    private LoginHelper() {
    }

    /**
     * 检查当前用户是否已登录（与源系统语义一致：未登录不抛异常）
     */
    public static boolean isLogin() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof LoginUser;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前登录用户ID（未登录返回 null，供媒体匿名调用）
     */
    public static Long getUserId() {
        try {
            return SecurityUtils.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前登录用户
     */
    public static LoginUser getLoginUser() {
        return SecurityUtils.getLoginUser();
    }
}
