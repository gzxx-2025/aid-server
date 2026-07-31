package com.aid.upgrade.dto;

import java.util.List;

import lombok.Data;

/**
 * 官方统一网关设置展示对象
 *
 * @author 视觉AID
 */
@Data
public class OfficialGatewaySettingVo {

    /** 是否启用官方统一网关 */
    private boolean enabled;

    /** 官方网关基础地址 */
    private String baseUrl;

    /** 脱敏后的官方网关密钥 */
    private String apiKeyMasked;

    /** 是否已配置密钥 */
    private boolean hasApiKey;

    /** 例外模型ID列表（例外模型仍走自有厂商网关） */
    private List<Long> excludedModelIds;

    /** 例外厂商ID列表（例外厂商下全部模型仍走自有厂商网关） */
    private List<Long> excludedProviderIds;
}
