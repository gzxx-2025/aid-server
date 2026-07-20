package com.aid.billing.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.enums.BillingMode;
import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.billing.service.BillingRuleResolver;
import com.aid.billing.util.SkuMatchUtil;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计费规则解析器实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingRuleResolverImpl implements BillingRuleResolver {

    private final ObjectMapper objectMapper;

    @Override
    public BillingRule parseRule(AiModelConfigVo modelConfig) {
        // FIXED模式直接返回null
        if (BillingMode.of(modelConfig.getBillingMode()) == BillingMode.FIXED) {
            return null;
        }
        String json = modelConfig.getBillingRuleJson();
        if (CharSequenceUtil.isBlank(json)) {
            log.warn("SKU模式但规则JSON为空, modelCode={}", modelConfig.getModelCode());
            return null;
        }
        try {
            BillingRule rule = objectMapper.readValue(json, BillingRule.class);
            // 基本校验：SKU列表不能为空
            if (CollectionUtil.isEmpty(rule.getSkus())) {
                log.error("计费规则SKU列表为空, modelCode={}", modelConfig.getModelCode());
                return null;
            }
            return rule;
        } catch (JsonProcessingException e) {
            log.error("计费规则JSON解析失败, modelCode={}, json={}", modelConfig.getModelCode(), json, e);
            return null;
        }
    }

    @Override
    public BillingSku resolve(BillingRule rule, Map<String, Object> params) {
        if (rule == null || CollectionUtil.isEmpty(rule.getSkus())) {
            return null;
        }
        // 按priority升序排序，只看enabled的SKU
        List<BillingSku> sortedSkus = rule.getSkus().stream()
                .filter(BillingSku::isEnabled)
                .sorted(Comparator.comparingInt(BillingSku::getPriority))
                .collect(Collectors.toList());

        // FIRST_HIT策略：返回第一个匹配的SKU
        for (BillingSku sku : sortedSkus) {
            if (SkuMatchUtil.isMatch(sku.getMatch(), params)) {
                return sku;
            }
        }
        return null;
    }
}
