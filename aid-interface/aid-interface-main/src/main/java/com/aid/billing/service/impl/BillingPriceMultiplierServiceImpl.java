package com.aid.billing.service.impl;

import cn.hutool.core.text.CharSequenceUtil;
import com.aid.aid.service.IAidConfigService;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.service.BillingPriceMultiplierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型价格倍率服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPriceMultiplierServiceImpl implements BillingPriceMultiplierService {

    /** 防止运营误配极端值造成金额溢出。 */
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("100000");

    private final IAidConfigService aidConfigService;

    private volatile BigDecimal cachedGlobalMultiplier = BillingConstants.DEFAULT_MODEL_PRICE_MULTIPLIER;
    private final AtomicLong globalMultiplierCacheTime = new AtomicLong(0);

    @Override
    public BigDecimal getGlobalMultiplier() {
        long now = System.currentTimeMillis();
        if (now - globalMultiplierCacheTime.get() < BillingConstants.MODEL_PRICE_MULTIPLIER_CACHE_TTL_MS) {
            return cachedGlobalMultiplier;
        }
        BigDecimal multiplier = BillingConstants.DEFAULT_MODEL_PRICE_MULTIPLIER;
        try {
            String raw = aidConfigService.getConfigValue(
                    BillingConstants.MODEL_PRICE_MULTIPLIER_CATEGORY,
                    BillingConstants.MODEL_PRICE_MULTIPLIER_KEY);
            if (CharSequenceUtil.isNotBlank(raw)) {
                BigDecimal parsed = new BigDecimal(raw.trim());
                if (parsed.compareTo(BigDecimal.ZERO) > 0) {
                    multiplier = parsed.min(MAX_MULTIPLIER);
                }
            }
        } catch (Exception e) {
            log.warn("读取模型基础倍率失败，按默认值处理, err={}", e.getMessage());
        }
        cachedGlobalMultiplier = multiplier;
        globalMultiplierCacheTime.set(now);
        return multiplier;
    }

    @Override
    public BigDecimal resolveModelMultiplier(BigDecimal modelMultiplier) {
        if (modelMultiplier != null && modelMultiplier.compareTo(BigDecimal.ZERO) > 0) {
            return modelMultiplier.min(MAX_MULTIPLIER);
        }
        return BigDecimal.ONE;
    }

    @Override
    public BigDecimal apply(BigDecimal rawPrice, BigDecimal modelMultiplier) {
        if (rawPrice == null) {
            return BigDecimal.ZERO;
        }
        return rawPrice.multiply(getGlobalMultiplier()).multiply(resolveModelMultiplier(modelMultiplier));
    }
}
