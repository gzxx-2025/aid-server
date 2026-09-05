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

/** 供应商低余额告警事件。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_balance_incident")
public class ProviderBalanceIncident extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String severity;
    private String status;
    private String triggerSource;
    private BigDecimal balance;
    private BigDecimal thresholdAmount;
    private String currency;
    private String reason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date openedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastTriggeredAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastNotifiedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date nextNotifyAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date acknowledgedAt;
    private String acknowledgedBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date resolvedAt;
}
