package com.aid.tokendance.credential;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.tokendance.domain.TokenDanceCredential;
import com.aid.tokendance.mapper.TokenDanceCredentialMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

/** TokenDance 凭证版本存储与解析服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenDanceCredentialStore {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RETIRED = "RETIRED";
    private static final String STATUS_REVOKED = "REVOKED";

    private final TokenDanceCredentialMapper credentialMapper;

    @Transactional(rollbackFor = Exception.class)
    public TokenDanceCredential activate(Long providerId, Long adminUserId, String username, String apiKey) {
        if (providerId == null || adminUserId == null || StrUtil.isBlank(apiKey)) {
            log.error("TokenDance 凭证保存参数不完整，providerId={}, adminUserId={}", providerId, adminUserId);
            throw new ServiceException("凭证保存失败");
        }
        if (credentialMapper.lockProvider(providerId) == null) {
            log.error("TokenDance 凭证保存时供应商不存在，providerId={}", providerId);
            throw new ServiceException("供应商不存在");
        }
        Integer maxVersion = credentialMapper.selectMaxVersion(providerId);
        int version = (maxVersion == null ? 0 : maxVersion) + 1;
        if (version <= 0) {
            log.error("TokenDance 凭证版本已耗尽，providerId={}", providerId);
            throw new ServiceException("凭证版本无效");
        }
        Date now = new Date();
        credentialMapper.update(null, Wrappers.<TokenDanceCredential>lambdaUpdate()
                .eq(TokenDanceCredential::getProviderId, providerId)
                .eq(TokenDanceCredential::getStatus, STATUS_ACTIVE)
                .set(TokenDanceCredential::getStatus, STATUS_RETIRED)
                .set(TokenDanceCredential::getRetiredAt, now)
                .set(TokenDanceCredential::getUpdateBy, username)
                .set(TokenDanceCredential::getUpdateTime, now));

        String normalized = apiKey.trim();
        TokenDanceCredential credential = new TokenDanceCredential();
        credential.setProviderId(providerId);
        credential.setCredentialVersion(version);
        credential.setAdminUserId(adminUserId);
        credential.setCredentialValue(normalized);
        credential.setCredentialFingerprint(sha256(normalized));
        credential.setCredentialHint(maskedHint(normalized));
        credential.setSourceType("OAUTH");
        credential.setStatus(STATUS_ACTIVE);
        credential.setAuthorizedAt(now);
        credential.setCreateBy(username);
        credential.setCreateTime(now);
        credential.setUpdateBy(username);
        credential.setUpdateTime(now);
        credentialMapper.insert(credential);
        // TokenDance 仍保留版本表作为历史任务的凭证快照；同时把当前活跃 Key 镜像到
        // 通用供应商字段，保证供应商/模型探活及后台“已配置”状态无需站长再次手填。
        if (credentialMapper.syncProviderApiKey(providerId, normalized, username, now) != 1) {
            log.error("TokenDance 活跃凭证同步供应商失败，providerId={}", providerId);
            throw new ServiceException("凭证保存失败");
        }
        return credential;
    }

    public TokenDanceCredential active(Long providerId) {
        if (providerId == null) {
            return null;
        }
        return credentialMapper.selectOne(Wrappers.<TokenDanceCredential>lambdaQuery()
                .select(TokenDanceCredential::getId, TokenDanceCredential::getProviderId,
                        TokenDanceCredential::getCredentialVersion, TokenDanceCredential::getAdminUserId,
                        TokenDanceCredential::getCredentialValue, TokenDanceCredential::getCredentialHint,
                        TokenDanceCredential::getSourceType, TokenDanceCredential::getStatus,
                        TokenDanceCredential::getAuthorizedAt, TokenDanceCredential::getCreateBy,
                        TokenDanceCredential::getCreateTime)
                .eq(TokenDanceCredential::getProviderId, providerId)
                .eq(TokenDanceCredential::getStatus, STATUS_ACTIVE)
                .orderByDesc(TokenDanceCredential::getCredentialVersion)
                .last("limit 1"));
    }

    public ResolvedTokenDanceCredential requireActive(Long providerId) {
        TokenDanceCredential credential = active(providerId);
        if (credential == null) {
            log.info("TokenDance 活跃凭证不存在，providerId={}", providerId);
            throw new ServiceException("请先授权凭证");
        }
        return resolve(credential);
    }

    public ResolvedTokenDanceCredential requireVersion(Long providerId, Integer credentialVersion) {
        TokenDanceCredential credential = credentialVersion == null ? null : credentialMapper.selectOne(
                Wrappers.<TokenDanceCredential>lambdaQuery()
                        .select(TokenDanceCredential::getId, TokenDanceCredential::getProviderId,
                                TokenDanceCredential::getCredentialVersion, TokenDanceCredential::getCredentialValue,
                                TokenDanceCredential::getStatus)
                        .eq(TokenDanceCredential::getProviderId, providerId)
                        .eq(TokenDanceCredential::getCredentialVersion, credentialVersion)
                        .last("limit 1"));
        if (credential == null) {
            log.error("TokenDance 凭证版本不存在，providerId={}, version={}", providerId, credentialVersion);
            throw new ServiceException("凭证版本无效");
        }
        if (!STATUS_ACTIVE.equals(credential.getStatus()) && !STATUS_RETIRED.equals(credential.getStatus())) {
            log.info("TokenDance 凭证版本已撤销，providerId={}, version={}", providerId, credentialVersion);
            throw new ServiceException("凭证已撤销");
        }
        return resolve(credential);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeActive(Long providerId, String username) {
        if (credentialMapper.lockProvider(providerId) == null) {
            log.error("TokenDance 凭证撤销时供应商不存在，providerId={}", providerId);
            throw new ServiceException("供应商不存在");
        }
        Date now = new Date();
        credentialMapper.update(null, Wrappers.<TokenDanceCredential>lambdaUpdate()
                .eq(TokenDanceCredential::getProviderId, providerId)
                .eq(TokenDanceCredential::getStatus, STATUS_ACTIVE)
                .set(TokenDanceCredential::getStatus, STATUS_REVOKED)
                .set(TokenDanceCredential::getRevokedAt, now)
                .set(TokenDanceCredential::getUpdateBy, username)
                .set(TokenDanceCredential::getUpdateTime, now));
        if (credentialMapper.clearProviderApiKey(providerId, username, now) != 1) {
            log.error("TokenDance 撤销凭证时清空供应商镜像失败，providerId={}", providerId);
            throw new ServiceException("凭证撤销失败");
        }
    }

    private ResolvedTokenDanceCredential resolve(TokenDanceCredential credential) {
        String apiKey = credential.getCredentialValue();
        return new ResolvedTokenDanceCredential(credential.getProviderId(),
                credential.getCredentialVersion(), apiKey);
    }

    private String maskedHint(String apiKey) {
        int suffixLength = Math.min(4, apiKey.length());
        return "****" + apiKey.substring(apiKey.length() - suffixLength);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            log.error("TokenDance 凭证指纹生成失败", ex);
            throw new ServiceException("凭证保存失败");
        }
    }
}
