package com.aid.tokendance.service;

import com.aid.tokendance.model.TokenDanceAccountModels;

/** TokenDance 账户授权、余额和充值服务。 */
public interface ITokenDanceAccountService {
    TokenDanceAccountModels.AuthorizationView startAuthorization(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.AuthorizationStartRequest request);

    TokenDanceAccountModels.AuthorizationView completeAuthorization(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.AuthorizationCompleteRequest request);

    TokenDanceAccountModels.AuthorizationView authorizationStatus(
            Long providerId, Long adminUserId, String authorizationRef);

    TokenDanceAccountModels.CredentialView credentialStatus(Long providerId);

    void revokeCredential(Long providerId, String username);

    TokenDanceAccountModels.BalanceView queryBalance(Long providerId, boolean force);

    TokenDanceAccountModels.PaymentView createPayment(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.PaymentCreateRequest request);

    TokenDanceAccountModels.PaymentView queryPayment(
            Long providerId, Long adminUserId, Long paymentId);
}
