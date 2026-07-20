package com.aid.notify.wechat.vo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 后台微信推送配置状态。
 */
@Data
public class WechatNotifyStatusVO
{
    private Boolean enabled;

    private Boolean wxLoginEnabled;

    private Boolean appIdConfigured;

    private Boolean secretConfigured;

    private Boolean tokenConfigured;

    private Boolean encodingAesKeyConfigured;

    private Boolean templateConfigured;

    private Boolean ready;

    private BigDecimal balanceReminderThreshold;

    private String wxLoginCategory;

    private List<String> missingItems = new ArrayList<>();

    private List<String> rules = new ArrayList<>();
}
