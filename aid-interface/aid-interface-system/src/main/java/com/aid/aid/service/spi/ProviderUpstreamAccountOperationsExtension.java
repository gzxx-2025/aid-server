package com.aid.aid.service.spi;

import com.aid.aid.domain.AidAiProvider;

import java.util.Map;

/** 可插拔的供应商账户查询扩展。 */
public interface ProviderUpstreamAccountOperationsExtension {
    boolean supports(String providerCode);

    Map<String, Object> capabilities(AidAiProvider provider);

    Map<String, Object> balance(AidAiProvider provider, Long startTime, Long endTime, String resourcePackName);
}
