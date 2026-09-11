package com.aid.notify.wechat.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.wxlogin.core.WxLoginTemplateFactory;
import com.aid.notify.wechat.config.WechatNotifyConfig;
import com.aid.notify.wechat.config.WechatNotifyTemplateConfig;
import com.aid.notify.wechat.service.IWechatNotifyConfigService;
import com.aid.notify.wechat.vo.WechatNotifyPublicVO;
import com.aid.notify.wechat.vo.WechatNotifyStatusVO;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 微信模板消息配置服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatNotifyConfigServiceImpl implements IWechatNotifyConfigService
{
    public static final String EVENT_BALANCE_INSUFFICIENT = "balance_insufficient";
    public static final String EVENT_BATCH_STARTED = "batch_started";
    public static final String EVENT_BATCH_SUCCEEDED = "batch_succeeded";
    public static final String EVENT_BATCH_FAILED = "batch_failed";
    public static final String EVENT_AUDIT_RESULT = "audit_result";
    public static final String EVENT_ORDER_REFUND = "order_refund";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_DAILY_LIMIT = 20;
    private static final int DEFAULT_MINUTE_LIMIT = 3;
    private static final BigDecimal DEFAULT_BALANCE_REMINDER_THRESHOLD = BigDecimal.ZERO;
    private static final long CONFIG_CACHE_MILLIS = 30_000L;

    private final IAidConfigService aidConfigService;
    private final WxLoginTemplateFactory wxLoginTemplateFactory;
    private volatile WechatNotifyConfig cachedConfig;
    private volatile long cachedConfigExpireAt;

    @Override
    public WechatNotifyConfig getConfig()
    {
        long now = System.currentTimeMillis();
        WechatNotifyConfig cache = cachedConfig;
        if (Objects.nonNull(cache) && now < cachedConfigExpireAt)
        {
            return cache;
        }
        WechatNotifyConfig config = null;
        try
        {
            String json = aidConfigService.getConfigValue(CATEGORY, CONFIG_NAME);
            if (StrUtil.isNotBlank(json))
            {
                config = OBJECT_MAPPER.readValue(json, WechatNotifyConfig.class);
            }
        }
        catch (Exception e)
        {
            log.warn("微信推送配置不存在或解析失败，使用默认配置: err={}", e.getMessage());
        }
        WechatNotifyConfig normalized = normalize(config);
        cachedConfig = normalized;
        cachedConfigExpireAt = now + CONFIG_CACHE_MILLIS;
        return normalized;
    }

    @Override
    public WechatNotifyConfig saveConfig(WechatNotifyConfig config)
    {
        WechatNotifyConfig normalized = normalize(config);
        try
        {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
            aidConfigService.upsertConfigValue(CATEGORY, CONFIG_NAME, json);
            cachedConfig = normalized;
            cachedConfigExpireAt = System.currentTimeMillis() + CONFIG_CACHE_MILLIS;
            return normalized;
        }
        catch (Exception e)
        {
            log.error("保存微信推送配置失败", e);
            throw new com.aid.common.exception.ServiceException("保存配置失败");
        }
    }

    @Override
    public WechatNotifyStatusVO getStatus()
    {
        WechatNotifyConfig config = getConfig();
        Map<String, String> wxConfig;
        boolean wxLoginEnabled;
        try
        {
            wxLoginEnabled = wxLoginTemplateFactory.isEnabled();
            wxConfig = wxLoginTemplateFactory.getCurrentConfig();
        }
        catch (Exception e)
        {
            log.warn("读取微信公众号登录配置失败: err={}", e.getMessage());
            wxLoginEnabled = false;
            wxConfig = new LinkedHashMap<>();
        }

        boolean appIdConfigured = StrUtil.isNotBlank(wxConfig.get("wxLoginAppId"));
        boolean secretConfigured = StrUtil.isNotBlank(wxConfig.get("wxLoginSecret"));
        boolean tokenConfigured = StrUtil.isNotBlank(wxConfig.get("wxLoginToken"));
        boolean aesConfigured = StrUtil.isNotBlank(wxConfig.get("wxLoginEncodingAESKey"))
                || StrUtil.isNotBlank(wxConfig.get("encodingAESKey"));
        boolean templateConfigured = allRequiredTemplatesConfigured(config);

        WechatNotifyStatusVO status = new WechatNotifyStatusVO();
        status.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        status.setWxLoginEnabled(wxLoginEnabled);
        status.setAppIdConfigured(appIdConfigured);
        status.setSecretConfigured(secretConfigured);
        status.setTokenConfigured(tokenConfigured);
        status.setEncodingAesKeyConfigured(aesConfigured);
        status.setTemplateConfigured(templateConfigured);
        status.setBalanceReminderThreshold(config.getBalanceReminderThreshold());
        status.setWxLoginCategory("wxLogin");
        status.setRules(getRules());

        List<String> missing = new ArrayList<>();
        if (!wxLoginEnabled) { missing.add("先开启微信公众号登录配置"); }
        if (!appIdConfigured) { missing.add("先配置微信公众号 AppId"); }
        if (!secretConfigured) { missing.add("先配置微信公众号 AppSecret"); }
        if (!tokenConfigured) { missing.add("先配置微信公众号 Token"); }
        if (!aesConfigured) { missing.add("先配置 EncodingAESKey"); }
        if (!templateConfigured) { missing.add("启用六个官方标准模板"); }
        status.setMissingItems(missing);
        status.setReady(Boolean.TRUE.equals(config.getEnabled()) && missing.isEmpty());
        return status;
    }

    @Override
    public WechatNotifyPublicVO getPublicStatus()
    {
        WechatNotifyConfig config = getConfig();
        return WechatNotifyPublicVO.builder()
                .enabled(Boolean.TRUE.equals(config.getEnabled()))
                .balanceReminderThreshold(config.getBalanceReminderThreshold())
                .rules(getRules())
                .build();
    }

    @Override
    public List<String> getRules()
    {
        List<String> rules = new ArrayList<>();
        rules.add("仅在用户绑定微信公众号并开启推送后发送。");
        rules.add("只推送全量批量生成的关键节点：开始、完成、失败或部分失败。");
        rules.add("单个生成、只选择部分ID生成、手动上传或普通编辑不会推送。");
        rules.add("内容提交审核、审核通过、审核驳回、发布成功和审核回撤会推送审核结果通知。");
        rules.add("支付订单全额退款成功后会推送退款通知。");
        rules.add("微信消息使用官方标准模板，详细消耗、余额和失败原因请进入系统查看。");
        WechatNotifyConfig config = getConfig();
        rules.add("余额提醒需先充值，且单次充值金额大于"
                + amountText(config.getBalanceReminderThreshold()) + "后才获得一次提醒资格。");
        rules.add("余额低于阈值或预扣费不足时会尝试提醒，成功一次后消耗本次资格。");
        return rules;
    }

    private WechatNotifyConfig normalize(WechatNotifyConfig input)
    {
        WechatNotifyConfig defaults = defaultConfig();
        if (Objects.isNull(input))
        {
            return defaults;
        }
        WechatNotifyConfig result = new WechatNotifyConfig();
        result.setEnabled(Boolean.TRUE.equals(input.getEnabled()));
        result.setJumpUrlBase(StrUtil.blankToDefault(input.getJumpUrlBase(), defaults.getJumpUrlBase()));
        result.setDailyUserLimit(safePositive(input.getDailyUserLimit(), DEFAULT_DAILY_LIMIT));
        result.setMinuteUserLimit(safePositive(input.getMinuteUserLimit(), DEFAULT_MINUTE_LIMIT));
        result.setBalanceReminderThreshold(safeNonNegative(input.getBalanceReminderThreshold(),
                DEFAULT_BALANCE_REMINDER_THRESHOLD));

        Map<String, WechatNotifyTemplateConfig> merged = new LinkedHashMap<>();
        for (Map.Entry<String, WechatNotifyTemplateConfig> entry : defaults.getTemplates().entrySet())
        {
            merged.put(entry.getKey(), fixedTemplate(entry.getValue()));
        }
        result.setTemplates(merged);
        return result;
    }

    private WechatNotifyTemplateConfig fixedTemplate(WechatNotifyTemplateConfig defaults)
    {
        WechatNotifyTemplateConfig result = new WechatNotifyTemplateConfig();
        result.setEnabled(Boolean.TRUE);
        result.setTitle(defaults.getTitle());
        result.setTemplateId(defaults.getTemplateId());
        Map<String, String> fields = new LinkedHashMap<>(defaults.getFields());
        result.setFields(fields);
        return result;
    }

    private boolean allRequiredTemplatesConfigured(WechatNotifyConfig config)
    {
        WechatNotifyConfig defaults = defaultConfig();
        for (Map.Entry<String, WechatNotifyTemplateConfig> entry : defaults.getTemplates().entrySet())
        {
            WechatNotifyTemplateConfig template = config.getTemplates().get(entry.getKey());
            if (Objects.isNull(template) || !Boolean.TRUE.equals(template.getEnabled())
                    || StrUtil.isBlank(template.getTemplateId()))
            {
                return false;
            }
            for (String field : entry.getValue().getFields().keySet())
            {
                if (template.getFields() == null || StrUtil.isBlank(template.getFields().get(field)))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private int safePositive(Integer value, int fallback)
    {
        if (value == null || value < 1)
        {
            return fallback;
        }
        return value;
    }

    private WechatNotifyConfig defaultConfig()
    {
        WechatNotifyConfig config = new WechatNotifyConfig();
        config.setEnabled(false);
        config.setJumpUrlBase("");
        config.setDailyUserLimit(DEFAULT_DAILY_LIMIT);
        config.setMinuteUserLimit(DEFAULT_MINUTE_LIMIT);
        config.setBalanceReminderThreshold(DEFAULT_BALANCE_REMINDER_THRESHOLD);
        Map<String, WechatNotifyTemplateConfig> templates = new LinkedHashMap<>();
        templates.put(EVENT_BALANCE_INSUFFICIENT, template("余额核验异常提醒",
                "uNI_iX6YcnDOLvsTB1trUeDOzaBK2rn9U3tpr89jzvU",
                field("accountName", "thing4"), field("currentBalance", "amount3"),
                field("alarmTime", "time5")));
        templates.put(EVENT_BATCH_STARTED, template("订单已开始通知",
                "DgHb3Pb9B7H6jB5V3Ubm59sILsInKeIy7ewgV6wP6Yo",
                field("serviceProject", "thing10"), field("startTime", "time4")));
        templates.put(EVENT_BATCH_SUCCEEDED, template("订单完成通知",
                "bAcI90AOnIoCUamhy5hWZFYISJdYWqkgSU4lEBDB-To",
                field("projectName", "thing19"), field("finishTime", "time18")));
        templates.put(EVENT_BATCH_FAILED, template("交易失败通知",
                "hLaniNK4118iL8hKO1itKOssx1n1xgtGPhX8H1zT4q0",
                field("productName", "thing2"), field("orderAmount", "amount3"),
                field("failureTime", "time4")));
        templates.put(EVENT_AUDIT_RESULT, template("项目数据提交审核结果通知",
                "ecvqMISxQCAJiUcyzf2yItc4imo7mVN-Zbsq6IjIpCY",
                field("projectName", "thing1"), field("finishTime", "time2"),
                field("auditResult", "const3")));
        templates.put(EVENT_ORDER_REFUND, template("退款通知",
                "XVkL-i8pVaz9LZT-fnmd-p9tRHJkLnUlQrx_GCImZPY",
                field("orderName", "thing8"), field("orderNo", "thing7"),
                field("refundReason", "thing6"), field("refundAmount", "amount2"),
                field("refundUser", "thing10")));
        config.setTemplates(templates);
        return config;
    }

    @SafeVarargs
    private final WechatNotifyTemplateConfig template(String title, String templateId,
                                                      Map.Entry<String, String>... fields)
    {
        WechatNotifyTemplateConfig template = new WechatNotifyTemplateConfig();
        template.setEnabled(true);
        template.setTitle(title);
        template.setTemplateId(templateId);
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields)
        {
            map.put(field.getKey(), field.getValue());
        }
        template.setFields(map);
        return template;
    }

    private Map.Entry<String, String> field(String name, String keyword)
    {
        return Map.entry(name, keyword);
    }

    private BigDecimal safeNonNegative(BigDecimal value, BigDecimal fallback)
    {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0)
        {
            return fallback;
        }
        return value;
    }

    private String amountText(BigDecimal amount)
    {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.stripTrailingZeros().toPlainString() + "积分";
    }
}
