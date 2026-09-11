package com.aid.tokendance.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.Date;

/** TokenDance 账户管理请求与响应模型。 */
public final class TokenDanceAccountModels {
    private TokenDanceAccountModels() {
    }

    @Data
    @Schema(name = "TokenDanceAuthorizationStartRequest", description = "API Key OAuth 授权发起参数")
    public static class AuthorizationStartRequest {
        @Schema(description = "授权模式：CALLBACK 或 HEADLESS", example = "CALLBACK")
        private String mode;
        @Schema(description = "可选 Key 名称，默认视觉AID，最多80字符；不改变固定 App URL 归因")
        private String keyName;
        @Schema(description = "后台生成的授权回调页面地址，回调模式必填")
        private String callbackUrl;
    }

    @Data
    @ToString(exclude = "code")
    @Schema(name = "TokenDanceAuthorizationCompleteRequest", description = "授权交换参数")
    public static class AuthorizationCompleteRequest {
        @Schema(description = "授权流程引用", requiredMode = Schema.RequiredMode.REQUIRED)
        private String authorizationRef;
        @Schema(description = "TokenDance 一次性授权码", requiredMode = Schema.RequiredMode.REQUIRED)
        private String code;
    }

    @Data
    @Schema(name = "TokenDanceAuthorizationView", description = "TokenDance OAuth 授权状态")
    public static class AuthorizationView {
        private String authorizationRef;
        private Long providerId;
        private String mode;
        private String status;
        private String authorizationUrl;
        private Integer credentialVersion;
        private String failureReason;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date expiresAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date exchangedAt;
    }

    @Data
    @Schema(name = "TokenDanceCredentialView", description = "TokenDance 凭证绑定状态")
    public static class CredentialView {
        private Long providerId;
        private boolean bound;
        private Integer credentialVersion;
        private String credentialHint;
        private String authorizedBy;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date authorizedAt;
    }

    @Data
    @Schema(name = "TokenDanceBalanceView", description = "TokenDance 官方账户余额")
    public static class BalanceView {
        private Long providerId;
        private Integer credentialVersion;
        private BigDecimal balance;
        private BigDecimal totalCredits;
        private BigDecimal creditsUsed;
        private String unit;
        private String sourceUnit;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date queriedAt;
    }

    @Data
    @Schema(name = "TokenDancePaymentCreateRequest", description = "TokenDance 充值会话创建参数")
    public static class PaymentCreateRequest {
        @Schema(description = "充值金额，单位元，只允许 1 至 100000 的整数", requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer amount;
        @Schema(description = "管理员已向付款人确认金额", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean userConfirmed;
        @Schema(description = "本次点击生成的幂等请求编号", requiredMode = Schema.RequiredMode.REQUIRED)
        private String requestId;
    }

    @Data
    @Schema(name = "TokenDancePaymentView", description = "TokenDance 充值会话")
    public static class PaymentView {
        private Long id;
        private Long providerId;
        private Integer credentialVersion;
        private Integer amount;
        private String status;
        private String paymentUrl;
        private String alipayUrl;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date expiredAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date paidAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date lastQueryTime;
        private String balanceRefreshStatus;
        private String failureReason;
    }
}
