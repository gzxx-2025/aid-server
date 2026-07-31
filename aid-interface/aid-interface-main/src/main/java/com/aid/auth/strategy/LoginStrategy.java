package com.aid.auth.strategy;

import com.aid.auth.domain.dto.LoginRequest;
import com.aid.common.core.domain.model.LoginUser;

/**
 * 登录策略接口
 *
 * @author 视觉AID
 */
public interface LoginStrategy {

    /**
     * 获取登录类型
     *
     * @return 登录类型编码
     */
    String getLoginType();

    /**
     * 执行登录
     *
     * @param request 登录请求参数
     * @return 登录用户信息
     */
    LoginUser login(LoginRequest request);

    /**
     * 校验登录参数
     *
     * @param request 登录请求参数
     */
    void validate(LoginRequest request);
}
