package com.aid.upgrade.dto;

import java.util.List;

import lombok.Data;

/**
 * 官方统一网关保存参数
 *
 * @author 视觉AID
 */
@Data
public class OfficialGatewaySaveDto {

    /** 是否启用官方统一网关 */
    private Boolean enabled;

    /** 官方网关基础地址，支持 {provider} 占位符 */
    private String baseUrl;

    /** 官方网关统一密钥（留空表示不修改） */
    private String apiKey;

    /** 例外模型ID列表（null表示不修改，空数组表示清空） */
    private List<Long> excludedModelIds;

    /** 例外厂商ID列表（null表示不修改，空数组表示清空） */
    private List<Long> excludedProviderIds;
}
