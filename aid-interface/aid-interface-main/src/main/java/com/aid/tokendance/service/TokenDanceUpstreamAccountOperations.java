package com.aid.tokendance.service;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.spi.ProviderUpstreamAccountOperationsExtension;
import com.aid.common.exception.ServiceException;
import com.aid.tokendance.model.TokenDanceAccountModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** TokenDance 供应商余额运维扩展。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenDanceUpstreamAccountOperations implements ProviderUpstreamAccountOperationsExtension {
    private static final String PROVIDER_CODE = "tokendance";

    private final ITokenDanceAccountService accountService;

    @Override
    public boolean supports(String providerCode) {
        return PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public Map<String, Object> capabilities(AidAiProvider provider) {
        TokenDanceAccountModels.CredentialView credential = accountService.credentialStatus(provider.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", true);
        result.put("upstreamTasks", false);
        result.put("balanceUnit", "CNY");
        result.put("balanceSourceUnit", "microCNY");
        result.put("supportsTimeRange", false);
        result.put("credentialBound", credential.isBound());
        result.put("credentialReady", credential.isBound());
        result.put("credentialVersion", credential.getCredentialVersion());
        result.put("balanceDelayNotice", "余额来自 TokenDance 官方账户接口");
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Map<String, Object> balance(
            AidAiProvider provider, Long startTime, Long endTime, String resourcePackName) {
        if (startTime != null || endTime != null || StrUtil.isNotBlank(resourcePackName)) {
            log.info("TokenDance 余额查询不支持筛选参数，providerId={}", provider.getId());
            throw new ServiceException("不支持余额筛选");
        }
        TokenDanceAccountModels.BalanceView balance = accountService.queryBalance(provider.getId(), false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", balance.getBalance());
        result.put("availableBalance", balance.getBalance());
        result.put("credits", balance.getTotalCredits());
        result.put("creditsUsed", balance.getCreditsUsed());
        result.put("unit", balance.getUnit());
        result.put("sourceUnit", balance.getSourceUnit());
        result.put("credentialVersion", balance.getCredentialVersion());
        result.put("queriedAt", balance.getQueriedAt() == null ? null : balance.getQueriedAt().getTime());
        return Collections.unmodifiableMap(result);
    }
}
