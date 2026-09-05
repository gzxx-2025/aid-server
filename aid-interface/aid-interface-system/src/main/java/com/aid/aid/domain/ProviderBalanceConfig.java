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

/** 供应商余额监控配置。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_balance_config")
public class ProviderBalanceConfig extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private Integer enabled;
    private Integer apiEnabled;
    private Integer simulatedEnabled;
    private Integer errorRuleEnabled;
    private Integer forecastEnabled;
    private String currency;
    private BigDecimal initialAmount;
    /** 官方基础价（通常为 CNY）换算为当前余额单位的倍率。 */
    private BigDecimal costUnitMultiplier;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date initialTime;
    private BigDecimal warningThreshold;
    private BigDecimal criticalThreshold;
    private BigDecimal recoveryThreshold;
    private Integer forecastDays;
    private Integer repeatIntervalMinutes;
    private Integer queryIntervalMinutes;
    private Integer confirmCount;
    private String currentStatus;
    private String currentSource;
    private BigDecimal currentBalance;
    private BigDecimal simulatedBalance;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastCheckTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastSuccessTime;
    private String lastError;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date silenceUntil;
    private Integer consecutiveLow;
}
