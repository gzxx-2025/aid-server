package com.aid.auth.controller;

import com.aid.auth.domain.dto.BindAccountRequest;
import com.aid.auth.domain.dto.CancelAccountRequest;
import com.aid.auth.domain.dto.LoginRequest;
import com.aid.auth.domain.dto.ResetPasswordRequest;
import com.aid.auth.domain.dto.SendCodeRequest;
import com.aid.auth.domain.dto.UnbindAccountRequest;
import com.aid.aid.domain.vo.LoginVO;
import com.aid.auth.service.AuthService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.captcha.annotation.CaptchaRequired;
import com.aid.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 统一认证控制器
 *
 * @author 视觉AID
 */
@Slf4j
@Tag(name = "认证管理", description = "登录、绑定、找回密码等接口")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthService authService;


    /**
     * 发送验证码
     *
     * @param request 发送验证码请求
     * @return 结果
     */
    @Operation(summary = "发送验证码", description = "发送短信或邮箱验证码")
    @Anonymous
    @CaptchaRequired(scene = "sendCode")
    @PostMapping("/sendCode")
    public AjaxResult sendCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendCode(request);
        return AjaxResult.success("发送成功");
    }

    /**
     * 查询 C 端首屏公开配置。
     *
     * @return 公开配置聚合对象（详见 {@link com.aid.auth.domain.vo.PublicConfigVO}）
     */
    @Operation(summary = "查询C端公开配置", description = "聚合行为验证码状态、短信/邮箱验证码策略、加密/支付/上传、营销活动等，前端首屏一次拉取")
    @Anonymous
    @PostMapping("/public-config")
    public AjaxResult publicConfig() {
        return AjaxResult.success(authService.getPublicConfig());
    }

    /**
     * 统一登录接口
     *
     * @param request 登录请求参数
     * @return 登录结果
     */
    @Operation(summary = "统一登录", description = "支持账号密码、短信、邮箱、微信等多种登录方式")
    @Anonymous
    @CaptchaRequired(scene = "login")
    @PostMapping("/login")
    public AjaxResult login(@Valid @RequestBody LoginRequest request) {
        LoginVO loginVO = authService.login(request);
        return AjaxResult.success(loginVO);
    }



    /**
     * 找回密码/重置密码
     *
     * @param request 重置密码请求
     * @return 结果
     */
    @Operation(summary = "找回密码", description = "通过手机号或邮箱重置密码")
    @Anonymous
    @PostMapping("/resetPassword")
    public AjaxResult resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return AjaxResult.success("密码重置成功");
    }



    /**
     * 退出登录
     *
     * @return 结果
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public AjaxResult logout() {
        authService.logout();
        return AjaxResult.success("退出成功");
    }


    /**
     * 绑定账号
     *
     * @param request 绑定请求
     * @return 结果
     */
    @Operation(summary = "绑定账号", description = "绑定手机号、邮箱或第三方账号")
    @PostMapping("/bind")
    public AjaxResult bindAccount(@Valid @RequestBody BindAccountRequest request) {
        authService.bindAccount(request);
        return AjaxResult.success("绑定成功");
    }

    /**
     * 解绑账号
     *
     * @param request 解绑请求
     * @return 结果
     */
    @Operation(summary = "解绑账号", description = "解绑手机号、邮箱或第三方账号")
    @PostMapping("/unbind")
    public AjaxResult unbindAccount(@Valid @RequestBody UnbindAccountRequest request) {
        authService.unbindAccount(request);
        return AjaxResult.success("解绑成功");
    }


    /**
     * 注销账号，需二次验证码确认，防止会话劫持或误操作一键注销。
     *
     * @return 结果
     */
    @Operation(summary = "注销账号", description = "注销当前登录账号（需要二次验证码）")
    @PostMapping("/cancel")
    public AjaxResult cancelAccount(@Valid @RequestBody CancelAccountRequest request) {
        authService.cancelAccount(request);
        return AjaxResult.success("账号已注销");
    }
}
