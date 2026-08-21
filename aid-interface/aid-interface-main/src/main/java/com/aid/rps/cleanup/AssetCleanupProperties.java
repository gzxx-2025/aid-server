package com.aid.rps.cleanup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 自动覆盖资产墓碑清理配置。
 *
 * @author 视觉AID
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "aid.asset-cleanup")
public class AssetCleanupProperties
{
    /** 是否启用清理。 */
    private boolean enabled = true;

    /** 墓碑最短保留天数。 */
    @Min(1)
    @Max(3650)
    private int retentionDays = 7;

    /** 单次最多处理的主资产数。 */
    @Min(1)
    @Max(1000)
    private int batchSize = 200;
}
