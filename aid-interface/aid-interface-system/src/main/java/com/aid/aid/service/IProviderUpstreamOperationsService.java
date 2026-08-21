package com.aid.aid.service;

import com.aid.aid.domain.dto.ProviderUpstreamTaskQuery;

import java.util.Map;

/** 后台供应商扩展能力统一入口，前端不按 providerCode 写分支。 */
public interface IProviderUpstreamOperationsService {
    Map<String, Object> capabilities(Long providerId);

    Map<String, Object> balance(Long providerId, Long startTime, Long endTime, String resourcePackName);

    Map<String, Object> tasks(Long providerId, ProviderUpstreamTaskQuery query);
}
