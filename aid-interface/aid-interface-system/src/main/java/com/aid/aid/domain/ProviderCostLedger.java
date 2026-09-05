package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Date;

/** 供应商理论成本台账，金额始终使用任务中的官方基础价快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_cost_ledger")
public class ProviderCostLedger extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventKey;
    private Long providerId;
    private Long modelId;
    private Long taskId;
    private String modelCode;
    private String entryType;
    private BigDecimal amount;
    private BigDecimal balanceDelta;
    private String currency;
    private String precisionType;
    private String pricingVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date occurredAt;
    private String detailJson;
}
