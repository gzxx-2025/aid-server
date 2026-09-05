package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** 供应商余额通知发送记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_balance_delivery")
public class ProviderBalanceDelivery extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private Long providerId;
    private Long recipientId;
    private String channel;
    private String deliveryType;
    private String status;
    private String messageId;
    private String errorMessage;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date attemptedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date succeededAt;
}
