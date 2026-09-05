package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 供应商余额提醒人。targetValue 保存邮箱、手机号或微信公众号 OpenID。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_balance_recipient")
public class ProviderBalanceRecipient extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recipientName;
    private String channel;
    @JsonIgnore
    private String targetValue;
    private String targetHash;
    private String displayValue;
    private String wechatNickname;
    private Integer enabled;
    private Integer dailyReportEnabled;
    private String providerIds;
}
