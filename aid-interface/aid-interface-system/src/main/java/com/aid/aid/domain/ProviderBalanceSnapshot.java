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

/** 供应商余额采样快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_balance_snapshot")
public class ProviderBalanceSnapshot extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String sourceType;
    private BigDecimal balance;
    private String currency;
    private String status;
    private String precisionType;
    private String detailJson;
    private String errorMessage;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date checkedAt;
}
