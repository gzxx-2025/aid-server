package com.aid.upgrade.gateway;

import java.util.Set;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * 官方统一网关运行时配置快照
 *
 * @author 视觉AID
 */
@Data
public class OfficialGatewayConfig {

    /** {provider} 占位符，按厂商编码展开 */
    public static final String PROVIDER_PLACEHOLDER = "{provider}";

    /** 是否启用官方统一网关 */
    private boolean enabled;

    /** 官方网关基础地址 */
    private String baseUrl;

    /** 官方网关统一密钥 */
    private String apiKey;

    /** 例外模型ID集合（例外模型仍走自有厂商网关） */
    private Set<Long> excludedModelIds;

    /** 例外厂商ID集合（例外厂商下全部模型仍走自有厂商网关） */
    private Set<Long> excludedProviderIds;

    /**
     * 判断模型是否为例外模型（例外模型不走官方网关）
     *
     * @param modelId 模型主键
     * @return true=例外，仍走自有厂商网关
     */
    public boolean isModelExcluded(Long modelId) {
        if (modelId == null || CollectionUtil.isEmpty(excludedModelIds)) {
            return false;
        }
        return excludedModelIds.contains(modelId);
    }

    /**
     * 判断厂商是否为例外厂商（例外厂商下全部模型不走官方网关）
     *
     * @param providerId 厂商主键
     * @return true=例外，该厂商下模型仍走自有厂商网关
     */
    public boolean isProviderExcluded(Long providerId) {
        if (providerId == null || CollectionUtil.isEmpty(excludedProviderIds)) {
            return false;
        }
        return excludedProviderIds.contains(providerId);
    }

    /**
     * 判断模型或其所属厂商是否例外（任一命中即不走官方网关）
     *
     * @param modelId    模型主键
     * @param providerId 厂商主键
     * @return true=例外，仍走自有厂商网关
     */
    public boolean isExcluded(Long modelId, Long providerId) {
        return isModelExcluded(modelId) || isProviderExcluded(providerId);
    }

    /**
     * 按厂商编码展开网关基础地址
     *
     * @param providerCode 厂商编码
     * @return 该厂商生效的网关基础地址
     */
    public String resolveBaseUrl(String providerCode) {
        if (StrUtil.isBlank(baseUrl) || !baseUrl.contains(PROVIDER_PLACEHOLDER)) {
            return baseUrl;
        }
        return baseUrl.replace(PROVIDER_PLACEHOLDER, StrUtil.nullToEmpty(providerCode));
    }
}
