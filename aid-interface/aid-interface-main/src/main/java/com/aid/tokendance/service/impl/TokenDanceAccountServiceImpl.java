package com.aid.tokendance.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.common.exception.ServiceException;
import com.aid.tokendance.client.TokenDancePortalClient;
import com.aid.tokendance.client.TokenDancePortalException;
import com.aid.tokendance.client.TokenDancePortalModels;
import com.aid.tokendance.config.TokenDanceAccountProperties;
import com.aid.tokendance.credential.ResolvedTokenDanceCredential;
import com.aid.tokendance.credential.TokenDanceCredentialStore;
import com.aid.tokendance.domain.TokenDanceCredential;
import com.aid.tokendance.domain.TokenDanceOAuthAuthorization;
import com.aid.tokendance.domain.TokenDancePaymentSession;
import com.aid.tokendance.mapper.TokenDanceOAuthAuthorizationMapper;
import com.aid.tokendance.mapper.TokenDancePaymentSessionMapper;
import com.aid.tokendance.model.TokenDanceAccountModels;
import com.aid.tokendance.provider.common.DefaultTokenDanceTransport;
import com.aid.tokendance.service.ITokenDanceAccountService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** TokenDance 账户授权、余额和充值实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenDanceAccountServiceImpl implements ITokenDanceAccountService {
    private static final String PROVIDER_CODE = "tokendance";
    private static final String MODE_CALLBACK = "CALLBACK";
    private static final String MODE_HEADLESS = "HEADLESS";
    private static final String AUTH_PENDING = "PENDING";
    private static final String AUTH_EXCHANGING = "EXCHANGING";
    private static final String AUTH_SUCCEEDED = "SUCCEEDED";
    private static final String AUTH_FAILED = "FAILED";
    private static final String AUTH_EXPIRED = "EXPIRED";
    private static final String PAYMENT_PAID = "PAID";
    private static final long AUTHORIZATION_LIFETIME_MILLIS = 10L * 60L * 1000L;
    private static final long PAYMENT_POLL_INTERVAL_MILLIS = 3_000L;
    private static final BigDecimal MICRO_CNY = new BigDecimal("1000000");

    private final IAidAiProviderService providerService;
    private final TokenDanceOAuthAuthorizationMapper authorizationMapper;
    private final TokenDancePaymentSessionMapper paymentMapper;
    private final TokenDanceCredentialStore credentialStore;
    private final TokenDancePortalClient portalClient;
    private final TokenDanceAccountProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<BalanceKey, BalanceCacheEntry> balanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BalanceKey, CompletableFuture<TokenDanceAccountModels.BalanceView>> balanceInflight
            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<TokenDanceAccountModels.PaymentView>> paymentInflight
            = new ConcurrentHashMap<>();

    @Override
    public TokenDanceAccountModels.AuthorizationView startAuthorization(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.AuthorizationStartRequest request) {
        requireProvider(providerId);
        requireAdmin(adminUserId);
        String mode = normalizeMode(request == null ? null : request.getMode());
        // OAuth 归因与模型请求头必须使用同一固定产品身份，不能由页面或部署参数分叉。
        String appUrl = DefaultTokenDanceTransport.APP_URL;
        String keyName = StrUtil.blankToDefault(request == null ? null : StrUtil.trim(request.getKeyName()),
                DefaultTokenDanceTransport.KEY_NAME);
        if (keyName.length() > 80 || keyName.chars().anyMatch(Character::isISOControl)) {
            throw new ServiceException("Key名称无效");
        }
        String authorizationRef = UUID.randomUUID().toString();
        String verifier = randomUrlToken(64);
        String callbackBase = MODE_CALLBACK.equals(mode)
                ? validateCallbackUrl(request == null ? null : request.getCallbackUrl()) : null;
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + AUTHORIZATION_LIFETIME_MILLIS);

        TokenDanceOAuthAuthorization authorization = new TokenDanceOAuthAuthorization();
        authorization.setAuthorizationRef(authorizationRef);
        authorization.setProviderId(providerId);
        authorization.setAdminUserId(adminUserId);
        authorization.setAuthorizationMode(mode);
        authorization.setVerifierValue(verifier);
        authorization.setCallbackUrl(callbackBase);
        authorization.setAppUrl(appUrl);
        authorization.setKeyName(keyName);
        authorization.setStatus(AUTH_PENDING);
        authorization.setExpiresAt(expiresAt);
        authorization.setCreateBy(username);
        authorization.setCreateTime(now);
        authorization.setUpdateBy(username);
        authorization.setUpdateTime(now);
        authorizationMapper.insert(authorization);

        TokenDanceAccountModels.AuthorizationView view = authorizationView(authorization);
        view.setAuthorizationUrl(buildAuthorizationUrl(callbackBase, verifier, appUrl, keyName));
        return view;
    }

    @Override
    public TokenDanceAccountModels.AuthorizationView completeAuthorization(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.AuthorizationCompleteRequest request) {
        requireProvider(providerId);
        requireAdmin(adminUserId);
        if (request == null || StrUtil.isBlank(request.getAuthorizationRef())) {
            log.info("TokenDance 授权缺少流程引用");
            throw new ServiceException("授权流程无效");
        }
        TokenDanceOAuthAuthorization authorization = findAuthorization(request.getAuthorizationRef());
        if (authorization == null || !Objects.equals(providerId, authorization.getProviderId())
                || !Objects.equals(adminUserId, authorization.getAdminUserId())) {
            log.info("TokenDance 授权绑定校验失败，providerId={}, adminUserId={}",
                    providerId, adminUserId);
            throw new ServiceException("授权流程无效");
        }
        return exchange(authorization, request.getCode(), username);
    }

    @Override
    public TokenDanceAccountModels.AuthorizationView authorizationStatus(
            Long providerId, Long adminUserId, String authorizationRef) {
        requireProvider(providerId);
        requireAdmin(adminUserId);
        TokenDanceOAuthAuthorization authorization = findAuthorization(authorizationRef);
        if (authorization == null || !Objects.equals(providerId, authorization.getProviderId())
                || !Objects.equals(adminUserId, authorization.getAdminUserId())) {
            log.info("TokenDance 授权状态查询未匹配绑定，providerId={}, adminUserId={}",
                    providerId, adminUserId);
            throw new ServiceException("授权流程无效");
        }
        expireIfNecessary(authorization);
        return authorizationView(authorization);
    }

    @Override
    public TokenDanceAccountModels.CredentialView credentialStatus(Long providerId) {
        requireProvider(providerId);
        TokenDanceCredential credential = credentialStore.active(providerId);
        TokenDanceAccountModels.CredentialView view = new TokenDanceAccountModels.CredentialView();
        view.setProviderId(providerId);
        view.setBound(credential != null);
        if (credential != null) {
            view.setCredentialVersion(credential.getCredentialVersion());
            view.setCredentialHint(credential.getCredentialHint());
            view.setAuthorizedBy(credential.getCreateBy());
            view.setAuthorizedAt(credential.getAuthorizedAt());
        }
        return view;
    }

    @Override
    public void revokeCredential(Long providerId, String username) {
        requireProvider(providerId);
        credentialStore.revokeActive(providerId, username);
        balanceCache.keySet().removeIf(key -> Objects.equals(key.providerId(), providerId));
    }

    @Override
    public TokenDanceAccountModels.BalanceView queryBalance(Long providerId, boolean force) {
        requireProvider(providerId);
        return queryBalance(credentialStore.requireActive(providerId), force);
    }

    @Override
    public TokenDanceAccountModels.PaymentView createPayment(
            Long providerId, Long adminUserId, String username,
            TokenDanceAccountModels.PaymentCreateRequest request) {
        requireProvider(providerId);
        requireAdmin(adminUserId);
        validatePaymentRequest(request);
        ResolvedTokenDanceCredential credential = credentialStore.requireActive(providerId);
        String requestHash = sha256(request.getRequestId().trim());
        TokenDancePaymentSession existing = findPaymentByRequest(providerId, adminUserId, requestHash);
        if (existing != null) {
            if (!Objects.equals(existing.getAmount(), request.getAmount())) {
                log.info("TokenDance 充值幂等编号被不同金额复用，paymentId={}", existing.getId());
                throw new ServiceException("请求编号已占用");
            }
            return paymentView(existing);
        }

        Date now = new Date();
        TokenDancePaymentSession payment = new TokenDancePaymentSession();
        payment.setProviderId(providerId);
        payment.setCredentialVersion(credential.getCredentialVersion());
        payment.setAdminUserId(adminUserId);
        payment.setRequestHash(requestHash);
        payment.setAmount(request.getAmount());
        payment.setStatus("CREATING");
        payment.setUserConfirmedAt(now);
        payment.setCreateBy(username);
        payment.setCreateTime(now);
        payment.setUpdateBy(username);
        payment.setUpdateTime(now);
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException ex) {
            existing = findPaymentByRequest(providerId, adminUserId, requestHash);
            if (existing == null) {
                log.error("TokenDance 充值幂等记录冲突后无法读取，providerId={}", providerId);
                throw new ServiceException("充值会话冲突");
            }
            if (!Objects.equals(existing.getAmount(), request.getAmount())) {
                throw new ServiceException("请求编号已占用");
            }
            return paymentView(existing);
        }

        try {
            TokenDancePortalModels.PaymentSession upstream = portalClient.createPaymentSession(
                    credential.getApiKey(), request.getAmount());
            if (upstream.getAmount() != null && !Objects.equals(upstream.getAmount(), request.getAmount())) {
                throw new TokenDancePortalException("充值响应异常", "AMOUNT_MISMATCH", true);
            }
            applyUpstreamPayment(payment, upstream);
            payment.setUpdateBy(username);
            payment.setUpdateTime(new Date());
            paymentMapper.updateById(payment);
            if (PAYMENT_PAID.equals(payment.getStatus())) {
                refreshBalanceOnce(payment, credential);
            }
            return paymentView(paymentMapper.selectById(payment.getId()));
        } catch (TokenDancePortalException ex) {
            payment.setStatus(ex.isOutcomeUncertain() ? "CREATE_UNKNOWN" : "CREATE_FAILED");
            payment.setLastErrorCode(ex.getErrorCode());
            payment.setUpdateBy(username);
            payment.setUpdateTime(new Date());
            paymentMapper.updateById(payment);
            throw new ServiceException(ex.getMessage());
        }
    }

    @Override
    public TokenDanceAccountModels.PaymentView queryPayment(
            Long providerId, Long adminUserId, Long paymentId) {
        requireProvider(providerId);
        requireAdmin(adminUserId);
        TokenDancePaymentSession payment = requirePayment(providerId, adminUserId, paymentId);
        if ("PENDING".equals(payment.getStatus()) && payment.getExpiredAt() != null
                && !payment.getExpiredAt().after(new Date())) {
            payment.setStatus("EXPIRED");
            payment.setUpdateTime(new Date());
            paymentMapper.updateById(payment);
            return paymentView(payment);
        }
        if (isTerminalPayment(payment.getStatus()) || !"PENDING".equals(payment.getStatus())) {
            return paymentView(payment);
        }
        if (payment.getLastQueryTime() != null
                && System.currentTimeMillis() - payment.getLastQueryTime().getTime() < PAYMENT_POLL_INTERVAL_MILLIS) {
            return paymentView(payment);
        }
        CompletableFuture<TokenDanceAccountModels.PaymentView> leader = new CompletableFuture<>();
        CompletableFuture<TokenDanceAccountModels.PaymentView> running = paymentInflight.putIfAbsent(paymentId, leader);
        if (running != null) {
            return await(running, "支付状态查询失败");
        }
        try {
            TokenDanceAccountModels.PaymentView view = doQueryPayment(payment);
            leader.complete(view);
            return view;
        } catch (RuntimeException ex) {
            leader.completeExceptionally(ex);
            throw ex;
        } finally {
            paymentInflight.remove(paymentId, leader);
        }
    }

    private TokenDanceAccountModels.AuthorizationView exchange(
            TokenDanceOAuthAuthorization authorization, String code, String username) {
        expireIfNecessary(authorization);
        if (AUTH_SUCCEEDED.equals(authorization.getStatus())) {
            return authorizationView(authorization);
        }
        if (AUTH_EXCHANGING.equals(authorization.getStatus())) {
            throw new ServiceException("授权正在处理");
        }
        if (!AUTH_PENDING.equals(authorization.getStatus())) {
            log.info("TokenDance OAuth 流程不可交换，authorizationRef={}, status={}",
                    authorization.getAuthorizationRef(), authorization.getStatus());
            throw new ServiceException("授权流程已结束");
        }
        String safeCode = StrUtil.trim(code);
        if (StrUtil.isBlank(safeCode) || safeCode.length() > 4096) {
            log.info("TokenDance OAuth 授权码无效，authorizationRef={}", authorization.getAuthorizationRef());
            throw new ServiceException("授权码无效");
        }
        String verifier = authorization.getVerifierValue();
        Date now = new Date();
        int claimed = authorizationMapper.update(null,
                Wrappers.<TokenDanceOAuthAuthorization>lambdaUpdate()
                        .eq(TokenDanceOAuthAuthorization::getId, authorization.getId())
                        .eq(TokenDanceOAuthAuthorization::getStatus, AUTH_PENDING)
                        .set(TokenDanceOAuthAuthorization::getStatus, AUTH_EXCHANGING)
                        .set(TokenDanceOAuthAuthorization::getUpdateBy, username)
                        .set(TokenDanceOAuthAuthorization::getUpdateTime, now));
        if (claimed != 1) {
            TokenDanceOAuthAuthorization latest = authorizationMapper.selectById(authorization.getId());
            if (latest != null && AUTH_SUCCEEDED.equals(latest.getStatus())) {
                return authorizationView(latest);
            }
            log.info("TokenDance OAuth 流程正在交换或已结束，authorizationRef={}",
                    authorization.getAuthorizationRef());
            throw new ServiceException("授权正在处理");
        }
        authorization.setStatus(AUTH_EXCHANGING);
        final String apiKey;
        try {
            apiKey = portalClient.exchangeApiKey(safeCode, verifier);
        } catch (TokenDancePortalException ex) {
            finishAuthorizationFailure(authorization, ex.getErrorCode(), username);
            throw new ServiceException(ex.getMessage());
        }
        final TokenDanceCredential credential;
        try {
            credential = credentialStore.activate(
                    authorization.getProviderId(), authorization.getAdminUserId(), username, apiKey);
        } catch (RuntimeException ex) {
            log.error("TokenDance OAuth Key 已返回但凭证保存失败，authorizationRef={}",
                    authorization.getAuthorizationRef(), ex);
            finishAuthorizationFailure(authorization, "CREDENTIAL_STORE_FAILED", username);
            throw new ServiceException("凭证保存失败");
        }
        authorization.setStatus(AUTH_SUCCEEDED);
        authorization.setCredentialVersion(credential.getCredentialVersion());
        authorization.setExchangedAt(new Date());
        authorization.setVerifierValue(null);
        authorization.setFailureCode(null);
        authorization.setUpdateBy(username);
        authorization.setUpdateTime(new Date());
        try {
            authorizationMapper.update(authorization, Wrappers.<TokenDanceOAuthAuthorization>lambdaUpdate()
                    .eq(TokenDanceOAuthAuthorization::getId, authorization.getId())
                    .eq(TokenDanceOAuthAuthorization::getStatus, AUTH_EXCHANGING)
                    .set(TokenDanceOAuthAuthorization::getVerifierValue, null));
        } catch (RuntimeException ex) {
            // Key 已安全保存，不能把成功授权误报成可重试的交换失败。
            log.error("TokenDance 凭证已保存但授权状态收口失败，authorizationRef={}",
                    authorization.getAuthorizationRef(), ex);
        }
        balanceCache.keySet().removeIf(key -> Objects.equals(key.providerId(), authorization.getProviderId()));
        return authorizationView(authorization);
    }

    private TokenDanceAccountModels.BalanceView queryBalance(
            ResolvedTokenDanceCredential credential, boolean force) {
        BalanceKey key = new BalanceKey(credential.getProviderId(), credential.getCredentialVersion());
        long now = System.currentTimeMillis();
        BalanceCacheEntry cached = balanceCache.get(key);
        long ttl = Math.max(0L, Math.min(properties.getBalanceCacheMillis(), 60_000L));
        if (!force && cached != null && now - cached.createdAt() < ttl) {
            return cached.value();
        }
        CompletableFuture<TokenDanceAccountModels.BalanceView> leader = new CompletableFuture<>();
        CompletableFuture<TokenDanceAccountModels.BalanceView> running = balanceInflight.putIfAbsent(key, leader);
        if (running != null) {
            return await(running, "余额查询失败");
        }
        try {
            TokenDancePortalModels.Balance raw = portalClient.queryBalance(credential.getApiKey());
            TokenDanceAccountModels.BalanceView view = new TokenDanceAccountModels.BalanceView();
            view.setProviderId(credential.getProviderId());
            view.setCredentialVersion(credential.getCredentialVersion());
            view.setBalance(toCny(raw.getBalanceMicros()));
            view.setTotalCredits(toCny(raw.getCreditsMicros()));
            view.setCreditsUsed(toCny(raw.getCreditsUsedMicros()));
            view.setUnit("CNY");
            view.setSourceUnit("microCNY");
            view.setQueriedAt(new Date());
            balanceCache.put(key, new BalanceCacheEntry(System.currentTimeMillis(), view));
            leader.complete(view);
            return view;
        } catch (RuntimeException ex) {
            leader.completeExceptionally(ex);
            throw ex;
        } finally {
            balanceInflight.remove(key, leader);
        }
    }

    private TokenDanceAccountModels.PaymentView doQueryPayment(TokenDancePaymentSession payment) {
        ResolvedTokenDanceCredential credential = credentialStore.requireVersion(
                payment.getProviderId(), payment.getCredentialVersion());
        payment.setLastQueryTime(new Date());
        payment.setUpdateTime(new Date());
        paymentMapper.updateById(payment);
        try {
            TokenDancePortalModels.PaymentSession upstream = portalClient.queryPaymentSession(
                    credential.getApiKey(), payment.getStatusUrl());
            if (!Objects.equals(payment.getUpstreamSessionId(), upstream.getSessionId())
                    || (upstream.getAmount() != null && !Objects.equals(payment.getAmount(), upstream.getAmount()))
                    || !Objects.equals(payment.getStatusUrl(), upstream.getStatusUrl())) {
                log.error("TokenDance 支付状态响应与本地会话不匹配，paymentId={}", payment.getId());
                throw new TokenDancePortalException("充值响应异常", "SESSION_MISMATCH", false);
            }
            applyUpstreamPayment(payment, upstream);
            payment.setLastErrorCode(null);
            payment.setUpdateTime(new Date());
            paymentMapper.updateById(payment);
            if (PAYMENT_PAID.equals(payment.getStatus())) {
                refreshBalanceOnce(payment, credential);
            }
            return paymentView(paymentMapper.selectById(payment.getId()));
        } catch (TokenDancePortalException ex) {
            payment.setLastErrorCode(ex.getErrorCode());
            payment.setUpdateTime(new Date());
            paymentMapper.updateById(payment);
            throw new ServiceException(ex.getMessage());
        }
    }

    private void refreshBalanceOnce(TokenDancePaymentSession payment, ResolvedTokenDanceCredential credential) {
        Date now = new Date();
        int claimed = paymentMapper.update(null, Wrappers.<TokenDancePaymentSession>lambdaUpdate()
                .eq(TokenDancePaymentSession::getId, payment.getId())
                .eq(TokenDancePaymentSession::getStatus, PAYMENT_PAID)
                .isNull(TokenDancePaymentSession::getBalanceRefreshStatus)
                .set(TokenDancePaymentSession::getBalanceRefreshStatus, "RUNNING")
                .set(TokenDancePaymentSession::getUpdateTime, now));
        if (claimed != 1) {
            return;
        }
        String status;
        try {
            queryBalance(credential, true);
            status = "SUCCESS";
        } catch (RuntimeException ex) {
            log.warn("TokenDance 充值到账后余额刷新失败，paymentId={}, error={}",
                    payment.getId(), ex.getClass().getSimpleName());
            status = "UNAVAILABLE";
        }
        paymentMapper.update(null, Wrappers.<TokenDancePaymentSession>lambdaUpdate()
                .eq(TokenDancePaymentSession::getId, payment.getId())
                .eq(TokenDancePaymentSession::getBalanceRefreshStatus, "RUNNING")
                .set(TokenDancePaymentSession::getBalanceRefreshStatus, status)
                .set(TokenDancePaymentSession::getBalanceRefreshedAt, new Date())
                .set(TokenDancePaymentSession::getUpdateTime, new Date()));
    }

    private void applyUpstreamPayment(
            TokenDancePaymentSession payment, TokenDancePortalModels.PaymentSession upstream) {
        payment.setUpstreamSessionId(upstream.getSessionId());
        payment.setStatus(upstream.getStatus().toUpperCase());
        if (StrUtil.isNotBlank(upstream.getPaymentUrl())) {
            payment.setPaymentUrl(upstream.getPaymentUrl());
        }
        if (StrUtil.isNotBlank(upstream.getAlipayUrl())) {
            payment.setAlipayUrl(upstream.getAlipayUrl());
        }
        payment.setStatusUrl(upstream.getStatusUrl());
        if (upstream.getCreatedAt() != null) {
            payment.setUpstreamCreatedAt(upstream.getCreatedAt());
        }
        if (upstream.getExpiredAt() != null) {
            payment.setExpiredAt(upstream.getExpiredAt());
        }
        if ("paid".equals(upstream.getStatus()) && upstream.getPaidAt() != null) {
            payment.setPaidAt(upstream.getPaidAt());
        }
    }

    private void finishAuthorizationFailure(
            TokenDanceOAuthAuthorization authorization, String failureCode, String username) {
        authorization.setStatus(AUTH_FAILED);
        authorization.setFailureCode(safeCode(failureCode));
        authorization.setVerifierValue(null);
        authorization.setUpdateBy(username);
        authorization.setUpdateTime(new Date());
        authorizationMapper.update(authorization, Wrappers.<TokenDanceOAuthAuthorization>lambdaUpdate()
                .eq(TokenDanceOAuthAuthorization::getId, authorization.getId())
                .eq(TokenDanceOAuthAuthorization::getStatus, AUTH_EXCHANGING)
                .set(TokenDanceOAuthAuthorization::getVerifierValue, null));
    }

    private void expireIfNecessary(TokenDanceOAuthAuthorization authorization) {
        if (authorization != null && AUTH_PENDING.equals(authorization.getStatus())
                && authorization.getExpiresAt() != null
                && authorization.getExpiresAt().before(new Date())) {
            authorization.setStatus(AUTH_EXPIRED);
            authorization.setVerifierValue(null);
            authorization.setFailureCode("AUTH_EXPIRED");
            authorization.setUpdateTime(new Date());
            authorizationMapper.update(authorization, Wrappers.<TokenDanceOAuthAuthorization>lambdaUpdate()
                    .eq(TokenDanceOAuthAuthorization::getId, authorization.getId())
                    .eq(TokenDanceOAuthAuthorization::getStatus, AUTH_PENDING)
                    .set(TokenDanceOAuthAuthorization::getVerifierValue, null));
        }
    }

    private TokenDanceOAuthAuthorization findAuthorization(String authorizationRef) {
        if (StrUtil.isBlank(authorizationRef) || authorizationRef.length() > 64) {
            return null;
        }
        return authorizationMapper.selectOne(Wrappers.<TokenDanceOAuthAuthorization>lambdaQuery()
                .eq(TokenDanceOAuthAuthorization::getAuthorizationRef, authorizationRef.trim())
                .last("limit 1"));
    }

    private TokenDancePaymentSession requirePayment(Long providerId, Long adminUserId, Long paymentId) {
        TokenDancePaymentSession payment = paymentId == null ? null : paymentMapper.selectOne(
                Wrappers.<TokenDancePaymentSession>lambdaQuery()
                        .eq(TokenDancePaymentSession::getId, paymentId)
                        .eq(TokenDancePaymentSession::getProviderId, providerId)
                        .eq(TokenDancePaymentSession::getAdminUserId, adminUserId)
                        .last("limit 1"));
        if (payment == null) {
            log.info("TokenDance 充值会话不存在或不属于当前管理员，paymentId={}", paymentId);
            throw new ServiceException("充值会话无效");
        }
        return payment;
    }

    private TokenDancePaymentSession findPaymentByRequest(
            Long providerId, Long adminUserId, String requestHash) {
        return paymentMapper.selectOne(Wrappers.<TokenDancePaymentSession>lambdaQuery()
                .eq(TokenDancePaymentSession::getProviderId, providerId)
                .eq(TokenDancePaymentSession::getAdminUserId, adminUserId)
                .eq(TokenDancePaymentSession::getRequestHash, requestHash)
                .last("limit 1"));
    }

    private TokenDanceAccountModels.AuthorizationView authorizationView(
            TokenDanceOAuthAuthorization authorization) {
        TokenDanceAccountModels.AuthorizationView view = new TokenDanceAccountModels.AuthorizationView();
        view.setAuthorizationRef(authorization.getAuthorizationRef());
        view.setProviderId(authorization.getProviderId());
        view.setMode(authorization.getAuthorizationMode());
        view.setStatus(authorization.getStatus());
        view.setCredentialVersion(authorization.getCredentialVersion());
        view.setExpiresAt(authorization.getExpiresAt());
        view.setExchangedAt(authorization.getExchangedAt());
        if (AUTH_EXPIRED.equals(authorization.getStatus())) {
            view.setFailureReason("授权已过期");
        } else if (AUTH_FAILED.equals(authorization.getStatus())) {
            view.setFailureReason("授权未完成");
        }
        return view;
    }

    private TokenDanceAccountModels.PaymentView paymentView(TokenDancePaymentSession payment) {
        TokenDanceAccountModels.PaymentView view = new TokenDanceAccountModels.PaymentView();
        view.setId(payment.getId());
        view.setProviderId(payment.getProviderId());
        view.setCredentialVersion(payment.getCredentialVersion());
        view.setAmount(payment.getAmount());
        view.setStatus(payment.getStatus());
        view.setPaymentUrl(payment.getPaymentUrl());
        view.setAlipayUrl(payment.getAlipayUrl());
        view.setExpiredAt(payment.getExpiredAt());
        view.setPaidAt(payment.getPaidAt());
        view.setLastQueryTime(payment.getLastQueryTime());
        view.setBalanceRefreshStatus(payment.getBalanceRefreshStatus());
        if (StrUtil.isNotBlank(payment.getLastErrorCode())) {
            view.setFailureReason("上游请求未完成");
        }
        return view;
    }

    private String buildAuthorizationUrl(String callbackUrl, String verifier, String appUrl, String keyName) {
        StringBuilder url = new StringBuilder(portalClient.authorizationEndpoint()).append('?');
        if (StrUtil.isNotBlank(callbackUrl)) {
            url.append("callback_url=").append(encode(callbackUrl)).append('&');
        }
        url.append("code_challenge=").append(encode(codeChallenge(verifier)))
                .append("&code_challenge_method=S256")
                .append("&app_url=").append(encode(appUrl))
                .append("&key_name=").append(encode(keyName));
        return url.toString();
    }

    private String normalizeMode(String raw) {
        String mode = StrUtil.blankToDefault(StrUtil.trim(raw), MODE_CALLBACK).toUpperCase();
        if (!MODE_CALLBACK.equals(mode) && !MODE_HEADLESS.equals(mode)) {
            log.info("TokenDance OAuth 授权模式无效");
            throw new ServiceException("授权模式无效");
        }
        return mode;
    }

    private String validateCallbackUrl(String value) {
        try {
            URI uri = URI.create(StrUtil.trim(value));
            // 公网 HTTP/IP 是否允许由 TokenDance 校验；这里只接受无凭证的 HTTP(S) 页面地址。
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || StrUtil.isBlank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getRawQuery() != null || value.length() > 1024
                    || !uri.getPath().endsWith("/tokendance/oauth/callback")) {
                throw new IllegalArgumentException("invalid callback url");
            }
            return uri.toString();
        } catch (Exception ex) {
            log.error("TokenDance OAuth 回调地址未配置或格式无效");
            throw new ServiceException("回调地址无效");
        }
    }

    private void validatePaymentRequest(TokenDanceAccountModels.PaymentCreateRequest request) {
        if (request == null || request.getAmount() == null
                || request.getAmount() < 1 || request.getAmount() > 100_000) {
            log.info("TokenDance 充值金额不在官方允许范围");
            throw new ServiceException("充值金额无效");
        }
        if (!Boolean.TRUE.equals(request.getUserConfirmed())) {
            log.info("TokenDance 充值未确认付款人授权");
            throw new ServiceException("请先确认充值");
        }
        String requestId = StrUtil.trim(request.getRequestId());
        if (StrUtil.isBlank(requestId) || !requestId.matches("[A-Za-z0-9._:-]{8,128}")) {
            log.info("TokenDance 充值幂等请求编号格式无效");
            throw new ServiceException("请求编号无效");
        }
    }

    private AidAiProvider requireProvider(Long providerId) {
        AidAiProvider provider = providerId == null ? null : providerService.selectAidAiProviderById(providerId);
        if (provider == null || !PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))) {
            log.info("TokenDance 账户操作供应商不匹配，providerId={}", providerId);
            throw new ServiceException("供应商不支持");
        }
        return provider;
    }

    private void requireAdmin(Long adminUserId) {
        if (adminUserId == null) {
            log.info("TokenDance 账户操作缺少管理员身份");
            throw new ServiceException("管理员身份无效");
        }
    }

    private boolean isTerminalPayment(String status) {
        return PAYMENT_PAID.equals(status) || "FAILED".equals(status)
                || "CLOSED".equals(status) || "REFUNDED".equals(status) || "EXPIRED".equals(status);
    }

    private BigDecimal toCny(BigDecimal micros) {
        if (micros == null) {
            throw new ServiceException("余额响应异常");
        }
        return micros.divide(MICRO_CNY);
    }

    private String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            log.error("TokenDance PKCE challenge 生成失败", ex);
            throw new ServiceException("授权初始化失败");
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            log.error("TokenDance 摘要生成失败", ex);
            throw new ServiceException("请求处理失败");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safeCode(String code) {
        String value = StrUtil.blankToDefault(StrUtil.trim(code), "UNKNOWN").toUpperCase();
        return value.matches("[A-Z0-9_-]{1,64}") ? value : "UNKNOWN";
    }

    private <T> T await(CompletableFuture<T> future, String message) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException(message);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ServiceException serviceException) {
                throw serviceException;
            }
            if (cause instanceof TokenDancePortalException portalException) {
                throw new ServiceException(portalException.getMessage());
            }
            throw new ServiceException(message);
        }
    }

    private record BalanceKey(Long providerId, Integer credentialVersion) {
    }

    private record BalanceCacheEntry(long createdAt, TokenDanceAccountModels.BalanceView value) {
    }
}
