package com.aid.auth.controller;

import com.aid.auth.domain.dto.ChangePasswordRequest;
import com.aid.auth.domain.dto.LoginHistoryRequest;
import com.aid.auth.domain.dto.RebindAccountRequest;
import com.aid.auth.domain.dto.SessionRevokeRequest;
import com.aid.auth.domain.dto.SetPasswordRequest;
import com.aid.auth.service.AuthService;
import com.aid.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C端账号安全控制器。
 *
 * @author 视觉AID
 */
@Tag(name = "账号安全", description = "密码、换绑、会话和登录记录接口")
@RestController
@RequestMapping("/api/user/account")
public class AccountSecurityController {

    @Resource
    private AuthService authService;

    @Operation(summary = "查询账号安全状态",
            description = "返回密码设置状态、手机号/邮箱/微信绑定状态、脱敏地址、可用登录方式、解绑能力、渠道开关、注销后再次注册限制和密码规则")
    @PostMapping("/security")
    public AjaxResult security() {
        return AjaxResult.success(authService.getAccountSecurity());
    }

    @Operation(summary = "首次设置密码",
            description = "仅用于尚未设置密码的账号；须先发送 set_password 场景验证码，成功后该账号全部会话失效")
    @PostMapping("/password/set")
    public AjaxResult setPassword(@Valid @RequestBody SetPasswordRequest request) {
        authService.setPassword(request);
        return AjaxResult.success("密码设置成功");
    }

    @Operation(summary = "修改密码",
            description = "校验旧密码并设置新密码；新密码须符合公开密码规则且不能与旧密码相同，成功后全部会话失效")
    @PostMapping("/password/change")
    public AjaxResult changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return AjaxResult.success("密码修改成功");
    }

    @Operation(summary = "换绑手机号或邮箱",
            description = "同时校验 rebind_old 旧地址验证码和 rebind_new 新地址验证码，成功后保留当前会话并退出其它会话")
    @PostMapping("/rebind")
    public AjaxResult rebind(@Valid @RequestBody RebindAccountRequest request) {
        authService.rebindAccount(request);
        return AjaxResult.success("换绑成功");
    }

    @Operation(summary = "查询在线会话",
            description = "返回当前账号全部有效会话及设备信息，只暴露不可逆会话标识，不返回真实 Token")
    @PostMapping("/session/list")
    public AjaxResult sessions() {
        return AjaxResult.success(authService.listAccountSessions());
    }

    @Operation(summary = "移除指定会话",
            description = "按会话公开标识移除当前账号的指定非当前会话")
    @PostMapping("/session/revoke")
    public AjaxResult revokeSession(@Valid @RequestBody SessionRevokeRequest request) {
        authService.revokeSession(request);
        return AjaxResult.success("会话已移除");
    }

    @Operation(summary = "退出其他会话",
            description = "保留当前会话并移除当前账号其它全部会话，返回移除数量")
    @PostMapping("/session/logout-others")
    public AjaxResult logoutOthers() {
        return AjaxResult.success(authService.logoutOtherSessions());
    }

    @Operation(summary = "退出全部会话",
            description = "移除当前账号全部会话并返回移除数量，当前请求使用的 Token 随即失效")
    @PostMapping("/session/logout-all")
    public AjaxResult logoutAll() {
        return AjaxResult.success(authService.logoutAllSessions());
    }

    @Operation(summary = "查询登录记录",
            description = "按当前登录用户ID分页查询本人登录历史；页码默认1，每页默认10条且最多100条")
    @PostMapping("/login-history")
    public AjaxResult loginHistory(@RequestBody(required = false) LoginHistoryRequest request) {
        return AjaxResult.success(authService.getLoginHistory(request));
    }
}
