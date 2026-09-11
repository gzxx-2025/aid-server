package com.aid.tokendance.controller;

import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.tokendance.model.TokenDanceAccountModels;
import com.aid.tokendance.service.ITokenDanceAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TokenDance 供应商账户后台管理接口。 */
@RestController
@RequestMapping("/aid/tokendance")
@RequiredArgsConstructor
@Tag(name = "TokenDance 账户", description = "TokenDance 凭证授权、余额与充值会话管理")
public class TokenDanceAccountController extends BaseController {
    private final ITokenDanceAccountService accountService;

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:edit')")
    @PostMapping("/providers/{providerId}/oauth/authorizations")
    @Operation(summary = "发起 API Key 授权", description = "使用 S256 PKCE 发起回调或 Headless 授权")
    public AjaxResult startAuthorization(
            @PathVariable Long providerId,
            @RequestBody(required = false) TokenDanceAccountModels.AuthorizationStartRequest request) {
        return success(accountService.startAuthorization(providerId, getUserId(), getUsername(), request));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:edit')")
    @PostMapping("/providers/{providerId}/oauth/authorizations/complete")
    @Operation(summary = "完成 API Key 授权", description = "交换浏览器回调或复制的一次性授权码，完整 Key 仅在服务端保存")
    public AjaxResult completeAuthorization(
            @PathVariable Long providerId,
            @RequestBody TokenDanceAccountModels.AuthorizationCompleteRequest request) {
        return success(accountService.completeAuthorization(
                providerId, getUserId(), getUsername(), request));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:query')")
    @GetMapping("/providers/{providerId}/oauth/authorizations/{authorizationRef}")
    @Operation(summary = "查询授权状态")
    public AjaxResult authorizationStatus(@PathVariable Long providerId,
                                          @PathVariable String authorizationRef) {
        return success(accountService.authorizationStatus(providerId, getUserId(), authorizationRef));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:query')")
    @GetMapping("/providers/{providerId}/credential")
    @Operation(summary = "查询凭证绑定状态", description = "仅返回版本和脱敏尾号，不返回完整 Key")
    public AjaxResult credentialStatus(@PathVariable Long providerId) {
        return success(accountService.credentialStatus(providerId));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:edit')")
    @PostMapping("/providers/{providerId}/credential/revoke")
    @Operation(summary = "撤销本地活跃凭证", description = "撤销后新调用和固定该版本的历史任务均不可再解析 Key")
    public AjaxResult revokeCredential(@PathVariable Long providerId) {
        accountService.revokeCredential(providerId, getUsername());
        return success();
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:query')")
    @GetMapping("/providers/{providerId}/balance")
    @Operation(summary = "查询 TokenDance 官方余额", description = "微元只在服务端换算一次为人民币")
    public AjaxResult balance(@PathVariable Long providerId,
                              @RequestParam(defaultValue = "false") boolean force) {
        return success(accountService.queryBalance(providerId, force));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:edit')")
    @PostMapping("/providers/{providerId}/payments")
    @Operation(summary = "创建 TokenDance 充值会话", description = "付款人确认金额后创建，不写入网站用户积分账本")
    public AjaxResult createPayment(
            @PathVariable Long providerId,
            @RequestBody TokenDanceAccountModels.PaymentCreateRequest request) {
        return success(accountService.createPayment(providerId, getUserId(), getUsername(), request));
    }

    @PreAuthorize("@ss.hasPermi('aid:aidprovider:query')")
    @PostMapping("/providers/{providerId}/payments/{paymentId}/status")
    @Operation(summary = "查询 TokenDance 充值状态", description = "仅官方状态精确为 paid 时确认到账")
    public AjaxResult paymentStatus(@PathVariable Long providerId, @PathVariable Long paymentId) {
        return success(accountService.queryPayment(providerId, getUserId(), paymentId));
    }
}
