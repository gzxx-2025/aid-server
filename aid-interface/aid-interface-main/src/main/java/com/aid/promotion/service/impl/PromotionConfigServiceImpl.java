package com.aid.promotion.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidConfigService;
import com.aid.promotion.constant.PromotionConstants;
import com.aid.promotion.domain.InviteConfig;
import com.aid.promotion.domain.RegisterBonusConfig;
import com.aid.promotion.service.IPromotionConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 营销配置读取Service实现
 * 每次实时读库（aid_config 无缓存层），营销链路调用频率低，实时性优先；
 * 任何配置缺失/格式错误都按「活动关闭」处理，绝不让配置问题阻断注册、登录、支付主流程。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionConfigServiceImpl implements IPromotionConfigService
{
    private final IAidConfigService aidConfigService;

    @Override
    public RegisterBonusConfig getRegisterBonusConfig()
    {
        RegisterBonusConfig config = new RegisterBonusConfig();
        // 默认全关：配置缺失时不发奖励，防止误发
        config.setEnabled(false);
        config.setAmount(BigDecimal.ZERO);
        config.setSmsEnabled(false);
        config.setEmailEnabled(false);
        config.setWechatEnabled(false);
        try
        {
            Map<String, String> values = loadCategory(PromotionConstants.CONFIG_CATEGORY_REGISTER_BONUS);
            config.setEnabled(parseBool(values.get(PromotionConstants.CONFIG_KEY_ENABLED)));
            config.setAmount(parseDecimal(values.get(PromotionConstants.CONFIG_KEY_AMOUNT)));
            config.setSmsEnabled(parseBool(values.get(PromotionConstants.CONFIG_KEY_SMS_ENABLED)));
            config.setEmailEnabled(parseBool(values.get(PromotionConstants.CONFIG_KEY_EMAIL_ENABLED)));
            config.setWechatEnabled(parseBool(values.get(PromotionConstants.CONFIG_KEY_WECHAT_ENABLED)));
        }
        catch (Exception e)
        {
            // 配置读取异常按活动关闭处理，不影响注册主流程
            log.error("读取注册送积分配置异常，按活动关闭处理", e);
        }
        return config;
    }

    @Override
    public InviteConfig getInviteConfig()
    {
        InviteConfig config = new InviteConfig();
        // 默认关闭：配置缺失时不绑定关系、不返佣
        config.setEnabled(false);
        config.setRebateRatio(BigDecimal.ZERO);
        config.setRebateMaxPerOrder(BigDecimal.ZERO);
        try
        {
            Map<String, String> values = loadCategory(PromotionConstants.CONFIG_CATEGORY_INVITE);
            config.setEnabled(parseBool(values.get(PromotionConstants.CONFIG_KEY_ENABLED)));
            config.setRebateRatio(parseDecimal(values.get(PromotionConstants.CONFIG_KEY_REBATE_RATIO)));
            config.setRebateMaxPerOrder(parseDecimal(values.get(PromotionConstants.CONFIG_KEY_REBATE_MAX_PER_ORDER)));
        }
        catch (Exception e)
        {
            // 配置读取异常按活动关闭处理，不影响支付主流程
            log.error("读取邀请激励配置异常，按活动关闭处理", e);
        }
        return config;
    }

    /**
     * 按分类整表读取配置为 Map（分类不存在时返回空 Map，不抛异常）
     *
     * @param category 配置分类
     * @return 配置名 → 配置值
     */
    private Map<String, String> loadCategory(String category)
    {
        AidConfig query = new AidConfig();
        query.setCategory(category);
        // 走 eq(category) 列表查询，分类为空时返回空列表而非抛异常
        List<AidConfig> list = aidConfigService.selectAidConfigList(query);
        if (CollectionUtil.isEmpty(list))
        {
            return Collections.emptyMap();
        }
        return list.stream()
                .filter(item -> StrUtil.isNotBlank(item.getConfigName()))
                .collect(Collectors.toMap(AidConfig::getConfigName, item ->
                        Objects.isNull(item.getConfigValue()) ? "" : item.getConfigValue(), (v1, v2) -> v2));
    }

    /**
     * 解析布尔配置：仅 "true"/"1" 视为开启，其余（含空）一律关闭
     */
    private boolean parseBool(String value)
    {
        if (StrUtil.isBlank(value))
        {
            return false;
        }
        String v = value.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * 解析数值配置：空或格式错误返回 0，负数按 0 处理（营销金额不允许为负）
     */
    private BigDecimal parseDecimal(String value)
    {
        if (StrUtil.isBlank(value))
        {
            return BigDecimal.ZERO;
        }
        try
        {
            BigDecimal parsed = new BigDecimal(value.trim());
            return parsed.signum() < 0 ? BigDecimal.ZERO : parsed;
        }
        catch (NumberFormatException e)
        {
            log.error("营销配置数值格式错误, value={}", value);
            return BigDecimal.ZERO;
        }
    }
}
