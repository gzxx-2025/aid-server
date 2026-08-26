package com.aid.billing.service.impl;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.enums.BillingMode;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.model.BillingSku;
import com.aid.billing.model.InputMediaPricing;
import com.aid.billing.model.SettleRule;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.service.BillingRuleResolver;
import com.aid.billing.util.TextTokenEstimator;
import com.aid.billing.util.BillingSettlementPolicy;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.ProviderUsageSupport;
import com.aid.media.provider.TextOutputLimitResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 计费金额计算器实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingAmountCalculatorImpl implements BillingAmountCalculator {

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final BillingRuleResolver billingRuleResolver;
    private final BillingPriceMultiplierService billingPriceMultiplierService;

    @Override
    public BillingCalcResult calculatePreHoldAmount(AiModelConfigVo modelConfig, BillingInput billingInput) {
        if (BillingMode.of(modelConfig.getBillingMode()) == BillingMode.FIXED) {
            MeterType meterType = resolveMeterType(modelConfig, null, null);
            log.info("预扣计费分发: modelCode={}, meterType={}", modelConfig.getModelCode(), meterType);
            return applyFreeDecision(modelConfig,
                    calculateFixedPreHold(modelConfig, billingInput, meterType));
        }

        BillingRule rule = billingRuleResolver.parseRule(modelConfig);
        if (rule == null) {
            log.error("SKU规则解析失败, modelCode={}", modelConfig.getModelCode());
            return BillingCalcResult.notMatched("计费规则缺失");
        }

        MeterType fallbackMeterType = resolveMeterType(modelConfig, rule, null);
        if (fallbackMeterType == null) {
            log.error("模型级meterType非法，拒绝计费, modelCode={}, meterType={}",
                    modelConfig.getModelCode(), rule.getMeterType());
            return BillingCalcResult.notMatched("计费规则无效");
        }
        if (fallbackMeterType == MeterType.TOKEN || hasEnabledTokenSku(rule)) {
            normalizeTextTokenPreHoldParams(modelConfig, billingInput, rule, MeterType.TOKEN);
        }

        BillingSku matchedSku = billingRuleResolver.resolve(rule, billingInput.getParams());
        if (matchedSku == null) {
            log.error("SKU未命中, modelCode={}, params={}", modelConfig.getModelCode(), billingInput.getParams());
            return BillingCalcResult.notMatched("计费规则缺失");
        }

        MeterType meterType = resolveMeterType(modelConfig, rule, matchedSku);
        if (meterType == null) {
            log.error("SKU级meterType非法，拒绝计费, modelCode={}, skuCode={}, meterType={}",
                    modelConfig.getModelCode(), matchedSku.getSkuCode(), matchedSku.getMeterType());
            return BillingCalcResult.notMatched("计费规则无效");
        }
        if (!hasValidMatchedSkuPrice(matchedSku, meterType)) {
            log.error("SKU主价格无效，拒绝计费, modelCode={}, skuCode={}, meterType={}",
                    modelConfig.getModelCode(), matchedSku.getSkuCode(), meterType);
            return BillingCalcResult.notMatched("计费规则无效");
        }
        log.info("预扣计费分发: modelCode={}, skuCode={}, meterType={}",
                modelConfig.getModelCode(), matchedSku.getSkuCode(), meterType);

        BillingCalcResult result = switch (meterType) {
            case TOKEN -> preHoldToken(modelConfig, matchedSku, rule, billingInput);
            case PER_IMAGE -> preHoldPerImage(modelConfig, matchedSku, rule, billingInput);
            case PER_SECOND -> preHoldPerSecond(modelConfig, matchedSku, rule, billingInput);
            case SKU_PACKAGE -> preHoldSkuPackage(modelConfig, matchedSku, rule, billingInput);
            case PER_CHAR -> preHoldPerChar(modelConfig, matchedSku, rule, billingInput);
        };
        return applyFreeDecision(modelConfig, result);
    }

    /** 在规则完整计算并命中后固化本次免费决定，原价、SKU 与倍率快照保持不变。 */
    private BillingCalcResult applyFreeDecision(AiModelConfigVo modelConfig, BillingCalcResult result) {
        if (result == null || !result.isMatched()) {
            return result;
        }
        boolean free = Boolean.TRUE.equals(modelConfig.getIsFree());
        BillingSnapshot snapshot = result.getSnapshot();
        if (snapshot != null) {
            snapshot.setIsFree(free);
        }
        if (free) {
            result.setAmount(BigDecimal.ZERO);
            if (snapshot != null) {
                snapshot.setPreHoldAmount(BigDecimal.ZERO);
            }
        }
        return result;
    }
    /**
     * 从模型配置解析计费计量类型。
     */
    private MeterType resolveMeterType(AiModelConfigVo modelConfig, BillingRule parsedRule,
                                       BillingSku matchedSku) {
        String modelCode = modelConfig.getModelCode();
        if (matchedSku != null && CharSequenceUtil.isNotBlank(matchedSku.getMeterType())) {
            MeterType skuMeterType = MeterType.of(matchedSku.getMeterType());
            if (skuMeterType != null) {
                return skuMeterType;
            }
            log.error("SKU级meterType无效, modelCode={}, skuCode={}, meterType={}",
                    modelCode, matchedSku.getSkuCode(), matchedSku.getMeterType());
            return null;
        }
        BillingRule rule = parsedRule;
        if (CharSequenceUtil.isNotBlank(modelConfig.getBillingRuleJson())) {
            try {
                if (rule == null) {
                    rule = billingRuleResolver.parseRule(modelConfig);
                }
                if (rule != null && CharSequenceUtil.isNotBlank(rule.getMeterType())) {
                    MeterType mt = MeterType.of(rule.getMeterType());
                    if (mt != null) {
                        return mt;
                    }
                    if (parsedRule != null || BillingMode.of(modelConfig.getBillingMode()) == BillingMode.SKU) {
                        log.error("模型级meterType无效, modelCode={}, meterType={}",
                                modelCode, rule.getMeterType());
                        return null;
                    }
                }
            } catch (Exception e) {
                log.warn("从billingRuleJson解析meterType失败, modelCode={}", modelCode);
            }
        }
        String modelType = modelConfig.getModelType();
        if ("TEXT".equalsIgnoreCase(modelType)) {
            log.warn("meterType未配置, 按modelType=TEXT兜底为TOKEN, modelCode={}", modelCode);
            return MeterType.TOKEN;
        } else if ("IMAGE".equalsIgnoreCase(modelType)) {
            if (isKnownTokenImageModel(modelCode)) {
                log.warn("meterType未配置, 但modelCode={}匹配已知TOKEN图片模型族, 兜底为TOKEN", modelCode);
                return MeterType.TOKEN;
            }
            log.warn("meterType未配置, 按modelType=IMAGE兜底为PER_IMAGE, modelCode={}", modelCode);
            return MeterType.PER_IMAGE;
        } else if ("VIDEO".equalsIgnoreCase(modelType)) {
            log.warn("meterType未配置, 按modelType=VIDEO兜底为SKU_PACKAGE, modelCode={}", modelCode);
            return MeterType.SKU_PACKAGE;
        }
        log.warn("meterType未配置且modelType未知, 兜底为SKU_PACKAGE, modelCode={}, modelType={}",
                modelCode, modelType);
        return MeterType.SKU_PACKAGE;
    }

    /** TOKEN 档位可能依赖估算后的 inputTokens 命中，匹配 SKU 前必须先完成保守估算。 */
    private boolean hasEnabledTokenSku(BillingRule rule) {
        return rule != null && rule.getSkus() != null && rule.getSkus().stream()
                .filter(Objects::nonNull)
                .filter(BillingSku::isEnabled)
                .map(BillingSku::getMeterType)
                .filter(CharSequenceUtil::isNotBlank)
                .map(MeterType::of)
                .anyMatch(MeterType.TOKEN::equals);
    }

    /** 显式 SKU 口径必须提供同单位主价格；仅无 SKU 口径的旧规则保留历史反推兜底。 */
    private boolean hasValidMatchedSkuPrice(BillingSku sku, MeterType meterType) {
        boolean explicitMeterType = CharSequenceUtil.isNotBlank(sku.getMeterType());
        return switch (meterType) {
            case TOKEN -> isPositive(sku.getInputPricePerMillion()) || isPositive(sku.getOutputPricePerMillion());
            case PER_IMAGE, SKU_PACKAGE -> isPositive(sku.getPrice());
            case PER_SECOND -> isPositive(sku.getPricePerSecond())
                    || (!explicitMeterType && isPositive(sku.getPrice())
                    && safeGetInt(sku.getMatch(), "durationMax", 0) > 0);
            case PER_CHAR -> isPositive(sku.getPricePerChar())
                    || (!explicitMeterType && isPositive(sku.getPrice()));
        };
    }

    /**
     * 判断是否为 TOKEN 计费图片模型。
     */
    private boolean isKnownTokenImageModel(String modelCode) {
        if (CharSequenceUtil.isBlank(modelCode)) {
            return false;
        }
        String lower = modelCode.toLowerCase();
        // Gemini 图片模型
        if (lower.contains("gemini") && lower.contains("image")) {
            return true;
        }
        // GPT-Image 系列（预留扩展）
        if (lower.contains("gpt") && lower.contains("image")) {
            return true;
        }
        return false;
    }
    /**
     * FIXED 模式预扣：按 meterType 计算 baseAmount。
     */
    private BillingCalcResult calculateFixedPreHold(AiModelConfigVo modelConfig, BillingInput billingInput, MeterType meterType) {
        BigDecimal unitPrice = modelConfig.getCostCredits() == null ? BigDecimal.ZERO : modelConfig.getCostCredits();
        BigDecimal baseAmount;
        BillingSnapshot snapshot = buildFixedSnapshot(modelConfig, unitPrice);
        snapshot.setMeterType(meterType.name());

        if (meterType == MeterType.PER_IMAGE) {
            int expectedImageCount = Math.max(1, safeGetInt(billingInput.getParams(), "expectedImageCount",
                    safeGetInt(billingInput.getParams(), "imageCount", 1)));
            baseAmount = unitPrice.multiply(BigDecimal.valueOf(expectedImageCount));
            fillImageSnapshot(snapshot, unitPrice, expectedImageCount, billingInput.getParams());
        } else if (meterType == MeterType.PER_SECOND) {
            int duration = safeGetInt(billingInput.getParams(), "duration", 5);
            baseAmount = unitPrice.multiply(BigDecimal.valueOf(duration));
            snapshot.setPricePerSecond(unitPrice);
            snapshot.setExpectedDurationSeconds(duration);
        } else {
            // TOKEN / SKU_PACKAGE：FIXED 模式直接用 costCredits
            baseAmount = unitPrice;
        }
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        logUnifiedPreHoldFormula(modelConfig, snapshot, meterType, baseAmount, adjusted);
        return BillingCalcResult.fixed(adjusted, snapshot);
    }
    /** TOKEN 预扣：inputTokens × inputPrice/M + outputTokens × outputPrice/M + 输入媒体附加费 */
    private BillingCalcResult preHoldToken(AiModelConfigVo modelConfig, BillingSku matchedSku,
                                           BillingRule rule, BillingInput billingInput) {
        BigDecimal baseAmount = calcPreHoldForTokenPricing(matchedSku, rule.getSettleRule(), billingInput.getParams());
        BillingSnapshot snapshot = buildTokenPricingSnapshot(modelConfig, matchedSku, rule, billingInput.getParams(), baseAmount);
        snapshot.setMeterType(MeterType.TOKEN.name());
        baseAmount = addInputMediaCharge(baseAmount, rule, matchedSku, billingInput.getParams(), snapshot, modelConfig);
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        logUnifiedPreHoldFormula(modelConfig, snapshot, MeterType.TOKEN, baseAmount, adjusted);
        return BillingCalcResult.sku(matchedSku.getSkuCode(), matchedSku.getSkuName(), adjusted, snapshot);
    }

    /** PER_IMAGE 预扣：unitPrice × expectedImageCount + 输入媒体附加费 */
    private BillingCalcResult preHoldPerImage(AiModelConfigVo modelConfig, BillingSku matchedSku,
                                              BillingRule rule, BillingInput billingInput) {
        BigDecimal unitPrice = matchedSku.getPrice() == null ? BigDecimal.ZERO : matchedSku.getPrice();
        int expectedImageCount = Math.max(1, safeGetInt(billingInput.getParams(), "expectedImageCount",
                safeGetInt(billingInput.getParams(), "imageCount", 1)));
        BigDecimal baseAmount = unitPrice.multiply(BigDecimal.valueOf(expectedImageCount));
        BillingSnapshot snapshot = buildSkuSnapshot(modelConfig, matchedSku, rule, billingInput.getParams());
        snapshot.setMeterType(MeterType.PER_IMAGE.name());
        fillImageSnapshot(snapshot, unitPrice, expectedImageCount, billingInput.getParams());
        baseAmount = addInputMediaCharge(baseAmount, rule, matchedSku, billingInput.getParams(), snapshot, modelConfig);
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        logUnifiedPreHoldFormula(modelConfig, snapshot, MeterType.PER_IMAGE, baseAmount, adjusted);
        return BillingCalcResult.sku(matchedSku.getSkuCode(), matchedSku.getSkuName(), adjusted, snapshot);
    }

    /** PER_SECOND 预扣：pricePerSecond × duration + 输入媒体附加费 */
    private BillingCalcResult preHoldPerSecond(AiModelConfigVo modelConfig, BillingSku matchedSku,
                                               BillingRule rule, BillingInput billingInput) {
        int duration = safeGetInt(billingInput.getParams(), "duration", 5);
        // 优先使用 SKU 的 pricePerSecond；未配置时从 match.durationMax 推算
        BigDecimal pps = resolvePerSecondPrice(matchedSku);
        BigDecimal baseAmount = pps.multiply(BigDecimal.valueOf(duration));
        BillingSnapshot snapshot = buildSkuSnapshot(modelConfig, matchedSku, rule, billingInput.getParams());
        snapshot.setMeterType(MeterType.PER_SECOND.name());
        snapshot.setPricePerSecond(pps);
        snapshot.setExpectedDurationSeconds(duration);
        baseAmount = addInputMediaCharge(baseAmount, rule, matchedSku, billingInput.getParams(), snapshot, modelConfig);
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        logUnifiedPreHoldFormula(modelConfig, snapshot, MeterType.PER_SECOND, baseAmount, adjusted);
        return BillingCalcResult.sku(matchedSku.getSkuCode(), matchedSku.getSkuName(), adjusted, snapshot);
    }

    /**
     * 解析每秒单价：优先 sku.pricePerSecond，
     * 未设置时用 sku.price / match.durationMax 推算（兼容旧整包价数据）。
     */
    private BigDecimal resolvePerSecondPrice(BillingSku sku) {
        if (sku.getPricePerSecond() != null && sku.getPricePerSecond().compareTo(BigDecimal.ZERO) > 0) {
            return sku.getPricePerSecond();
        }
        // 兜底：从整包价反推
        BigDecimal totalPrice = sku.getPrice() == null ? BigDecimal.ZERO : sku.getPrice();
        int maxDur = safeGetInt(sku.getMatch(), "durationMax", 0);
        if (maxDur > 0 && totalPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pps = totalPrice.divide(BigDecimal.valueOf(maxDur), 6, RoundingMode.HALF_UP);
            log.info("PER_SECOND兜底推算: skuCode={}, totalPrice={}, durationMax={}, pricePerSecond={}",
                    sku.getSkuCode(), totalPrice, maxDur, pps);
            return pps;
        }
        return totalPrice;
    }

    /**
     * PER_CHAR 预扣（TTS 按字符计费）：pricePerChar × chars。
     * chars 由 BillingInputExtractor.fromAudioRequest 提取（Java 字符数，含硬上限截断）。
     */
    private BillingCalcResult preHoldPerChar(AiModelConfigVo modelConfig, BillingSku matchedSku,
                                             BillingRule rule, BillingInput billingInput) {
        // 每字符单价：优先 pricePerChar，未配置时降级到 price（视为单次固定价兜底）
        BigDecimal pricePerChar = matchedSku.getPricePerChar();
        int chars = safeGetInt(billingInput.getParams(), "chars",
                safeGetInt(billingInput.getParams(), "textLength", 0));
        BigDecimal baseAmount;
        if (pricePerChar != null && pricePerChar.compareTo(BigDecimal.ZERO) > 0 && chars > 0) {
            baseAmount = pricePerChar.multiply(BigDecimal.valueOf(chars));
        } else {
            // 单价缺失或字符数为 0：按 SKU 固定价兜底，避免免费放行
            baseAmount = matchedSku.getPrice() == null ? BigDecimal.ZERO : matchedSku.getPrice();
        }
        BillingSnapshot snapshot = buildSkuSnapshot(modelConfig, matchedSku, rule, billingInput.getParams());
        snapshot.setMeterType(MeterType.PER_CHAR.name());
        snapshot.setUnitPrice(pricePerChar);
        snapshot.setUnitPriceType("PER_CHAR");
        baseAmount = addInputMediaCharge(baseAmount, rule, matchedSku, billingInput.getParams(), snapshot, modelConfig);
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        log.info("[预冻结-PER_CHAR] pricePerChar={} * chars={} = {}, model={}",
                pricePerChar, chars, baseAmount, modelConfig.getModelCode());
        BigDecimal finalAmount = adjusted;
        logUnifiedPreHoldFormula(modelConfig, snapshot, MeterType.PER_CHAR, baseAmount, finalAmount);
        return BillingCalcResult.sku(matchedSku.getSkuCode(), matchedSku.getSkuName(), finalAmount, snapshot);
    }

    /** SKU_PACKAGE 预扣：matchedSku.price（整包价）+ 输入媒体附加费 */
    private BillingCalcResult preHoldSkuPackage(AiModelConfigVo modelConfig, BillingSku matchedSku,
                                                BillingRule rule, BillingInput billingInput) {
        BigDecimal baseAmount = matchedSku.getPrice() == null ? BigDecimal.ZERO : matchedSku.getPrice();
        BillingSnapshot snapshot = buildSkuSnapshot(modelConfig, matchedSku, rule, billingInput.getParams());
        snapshot.setMeterType(MeterType.SKU_PACKAGE.name());
        snapshot.setSkuPackagePrice(baseAmount);
        baseAmount = addInputMediaCharge(baseAmount, rule, matchedSku, billingInput.getParams(), snapshot, modelConfig);
        snapshot.setPreHoldAmount(baseAmount);
        BigDecimal adjusted = applyMultipliersAndSnapshot(modelConfig, baseAmount, snapshot);
        logUnifiedPreHoldFormula(modelConfig, snapshot, MeterType.SKU_PACKAGE, baseAmount, adjusted);
        return BillingCalcResult.sku(matchedSku.getSkuCode(), matchedSku.getSkuName(), adjusted, snapshot);
    }

    /**
     * 输入媒体附加费统一入口：baseAmount + 参考图按张费 + 输入视频按秒费。
     * 取价优先级：SKU 级 inputPricing 覆盖规则级；单价 null 或 0 = 不计费（配 0 即免费）。
     * 图片输入支持 freeCount 表达前 N 张免费；未配置时按 0 张免费兼容旧规则。
     * 附加费基础额、分项单价与计费量写入快照；上游返回实际输入用量时重算，否则沿用预冻结值。
     */
    private BigDecimal addInputMediaCharge(BigDecimal baseAmount, BillingRule rule, BillingSku sku,
                                           Map<String, Object> params, BillingSnapshot snapshot,
                                           AiModelConfigVo modelConfig) {
        InputMediaPricing pricing = resolveInputPricing(rule, sku);
        if (pricing == null) {
            return baseAmount;
        }
        BigDecimal extra = BigDecimal.ZERO;
        // 图片输入：max(referenceImageCount - freeCount, 0) × unitPrice（总张数先按 maxCount 截断）
        InputMediaPricing.ImagePricing image = pricing.getImage();
        if (image != null && isPositive(image.getUnitPrice())) {
            int count = safeGetInt(params, "referenceImageCount", 0);
            if (image.getMaxCount() != null && image.getMaxCount() > 0) {
                count = Math.min(count, image.getMaxCount());
            }
            int freeCount = Math.max(image.getFreeCount() == null ? 0 : image.getFreeCount(), 0);
            int billedCount = Math.max(count - freeCount, 0);
            snapshot.setInputImageUnitPrice(image.getUnitPrice());
            snapshot.setInputImageFreeCount(freeCount);
            snapshot.setBilledInputImageCount(billedCount);
            if (billedCount > 0) {
                BigDecimal imageAmount = image.getUnitPrice().multiply(BigDecimal.valueOf(billedCount));
                snapshot.setInputImageAmount(imageAmount);
                extra = extra.add(imageAmount);
            }
        }
        // 视频输入：inputVideoSeconds × unitPrice（按 maxSeconds 截断）；
        // 有输入视频但时长未知时按 maxSeconds 预扣（宁高勿低，任务失败全额退）
        InputMediaPricing.VideoPricing video = pricing.getVideo();
        if (video != null && isPositive(video.getUnitPrice())) {
            snapshot.setInputVideoUnitPrice(video.getUnitPrice());
            int seconds = safeGetInt(params, "inputVideoSeconds", 0);
            int videoCount = safeGetInt(params, "inputVideoCount", 0);
            int maxSeconds = video.getMaxSeconds() == null ? 0 : video.getMaxSeconds();
            if (seconds <= 0 && videoCount > 0 && maxSeconds > 0) {
                seconds = maxSeconds;
            }
            if (maxSeconds > 0 && seconds > maxSeconds) {
                seconds = maxSeconds;
            }
            if (seconds > 0) {
                BigDecimal videoAmount = video.getUnitPrice().multiply(BigDecimal.valueOf(seconds));
                snapshot.setInputVideoAmount(videoAmount);
                extra = extra.add(videoAmount);
                snapshot.setBilledInputVideoSeconds(seconds);
            }
        }
        if (extra.compareTo(BigDecimal.ZERO) <= 0) {
            return baseAmount;
        }
        snapshot.setInputMediaAmount(extra);
        log.info("[预冻结-输入媒体] model={}, 输入图张数={}, 输入视频秒数={}, 附加费={} (图单价={}, 视频秒价={})",
                modelConfig.getModelCode(), snapshot.getBilledInputImageCount(), snapshot.getBilledInputVideoSeconds(),
                extra, image == null ? null : image.getUnitPrice(), video == null ? null : video.getUnitPrice());
        return baseAmount.add(extra);
    }

    /** 合并输入媒体计费配置：SKU 级按媒体类型覆盖规则级；两级都未配置返回 null */
    private InputMediaPricing resolveInputPricing(BillingRule rule, BillingSku sku) {
        InputMediaPricing skuLevel = sku == null ? null : sku.getInputPricing();
        InputMediaPricing ruleLevel = rule == null ? null : rule.getInputPricing();
        if (skuLevel == null) {
            return ruleLevel;
        }
        if (ruleLevel == null) {
            return skuLevel;
        }
        InputMediaPricing merged = new InputMediaPricing();
        merged.setImage(skuLevel.getImage() != null ? skuLevel.getImage() : ruleLevel.getImage());
        merged.setVideo(skuLevel.getVideo() != null ? skuLevel.getVideo() : ruleLevel.getVideo());
        return merged;
    }

    /** 从快照读取输入媒体附加费基础额（旧快照无该字段时按 0） */
    private BigDecimal snapshotInputMediaBase(BillingSnapshot snapshot) {
        if (snapshot != null && snapshot.getInputMediaAmount() != null
                && snapshot.getInputMediaAmount().compareTo(BigDecimal.ZERO) > 0) {
            return snapshot.getInputMediaAmount();
        }
        return BigDecimal.ZERO;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
    /**
     * 计算 finalBillingMultiplier = modelMultiplier × globalMultiplier，并将所有倍率字段+基础金额写入快照。
     * 返回 baseAmount × finalMultiplier。所有倍率为空或异常时按 1.00 兜底，绝不让计费链路炸掉。
     */
    private BigDecimal applyMultipliersAndSnapshot(AiModelConfigVo modelConfig, BigDecimal baseAmount, BillingSnapshot snapshot) {
        BigDecimal modelMultiplier = billingPriceMultiplierService.resolveModelMultiplier(
                modelConfig == null ? null : modelConfig.getBillingMultiplier());
        BigDecimal globalMultiplier = billingPriceMultiplierService.getGlobalMultiplier();
        BigDecimal finalMultiplier = modelMultiplier.multiply(globalMultiplier);
        if (snapshot != null) {
            // 审计字段：基础金额 + 三个倍率
            snapshot.setBaseAmount(baseAmount);
            snapshot.setModelBillingMultiplier(modelMultiplier);
            snapshot.setGlobalBillingMultiplier(globalMultiplier);
            snapshot.setFinalBillingMultiplier(finalMultiplier);
        }
        // 倍率计算保留原始精度，进入统一账户账本前由调用方规整为 4 位小数。
        BigDecimal adjusted = baseAmount.multiply(finalMultiplier);
        log.info("应用计费倍率, model={}, base={}, modelMul={}, globalMul={}, finalMul={}, adjusted={}",
                modelConfig.getModelCode(), baseAmount, modelMultiplier, globalMultiplier, finalMultiplier, adjusted);
        return adjusted;
    }

    /** 从快照读取最终综合倍率：未保存或异常时按 1.00 兜底（兼容旧任务） */
    private BigDecimal resolveFinalMultiplierFromSnapshot(BillingSnapshot snapshot) {
        if (snapshot != null && snapshot.getFinalBillingMultiplier() != null
                && snapshot.getFinalBillingMultiplier().compareTo(BigDecimal.ZERO) > 0) {
            return snapshot.getFinalBillingMultiplier();
        }
        return BigDecimal.ONE;
    }

    /**
     * 补全图片计费快照：写入 unitPrice / expectedImageCount / unitPriceType / generateMode。
     * 结算时按 actualImageCount × unitPrice 计算实际金额。
     */
    private void fillImageSnapshot(BillingSnapshot snapshot, BigDecimal unitPrice,
                                    int expectedImageCount, Map<String, Object> requestParams) {
        if (snapshot == null) {
            return;
        }
        snapshot.setUnitPrice(unitPrice);
        snapshot.setExpectedImageCount(expectedImageCount);
        snapshot.setUnitPriceType("PER_IMAGE");
        if (requestParams != null) {
            Object mode = requestParams.get("generateMode");
            if (mode != null) {
                snapshot.setGenerateMode(String.valueOf(mode));
            }
        }
    }

    @Override
    public BillingCalcResult calculateSettleAmount(BigDecimal preHoldAmount,
                                                   String snapshotJson, Map<String, Object> usageData) {
        // 从快照中取出原始计费信息（不依赖实时模型配置）
        BillingSnapshot snapshot = parseSnapshot(snapshotJson);
        if (snapshot == null) {
            return BillingCalcResult.fixed(preHoldAmount, null);
        }

        // 免费决定来自任务创建时快照；历史快照字段为空按收费兼容，绝不读取实时模型状态。
        if (Boolean.TRUE.equals(snapshot.getIsFree())) {
            snapshot.setPreHoldAmount(BigDecimal.ZERO);
            markSettleDone(snapshot, BigDecimal.ZERO, BigDecimal.ZERO);
            return BillingCalcResult.fixed(BigDecimal.ZERO, snapshot);
        }

        // 解析快照中的 meterType
        MeterType meterType = MeterType.of(snapshot.getMeterType());
        log.info("结算计费分发: modelName={}, meterType={}", snapshot.getModelName(), meterType);

        // FIXED模式或无规则JSON：直接按预扣金额结算
        String billingMode = snapshot.getBillingMode();
        String ruleJson = snapshot.getBillingRuleJson();
        if (BillingMode.FIXED.name().equalsIgnoreCase(billingMode) || ruleJson == null || ruleJson.isBlank()) {
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        // 按 meterType 分发结算逻辑
        if (meterType == MeterType.TOKEN) {
            // TOKEN：按实际 token 用量结算
            return settleWithTokenPricing(preHoldAmount, snapshot, usageData);
        } else if (meterType == MeterType.PER_IMAGE) {
            // PER_IMAGE：按实际张数结算（结算在 BillingFacadeServiceImpl 中完成，此处兜底）
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        } else if (meterType == MeterType.PER_SECOND) {
            if (BillingSettlementPolicy.isEstimated(meterType.name(), billingMode, ruleJson)) {
                // 依赖上游真实时长或允许退款：按实际秒数 × 每秒单价结算。
                return settleWithPerSecondPricing(preHoldAmount, snapshot, usageData);
            }
            // 显式 DIRECT_SETTLE + ESTIMATE：请求时长就是最终计费量。
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.sku(snapshot.getSkuCode(), snapshot.getSkuName(), preHoldAmount, snapshot);
        } else if (meterType == MeterType.SKU_PACKAGE) {
            // SKU_PACKAGE：直接按预扣金额结算
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        } else if (meterType == MeterType.PER_CHAR) {
            // PER_CHAR（TTS）：输入文本在预扣时已确定，直接按预扣金额结算
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        // meterType 未设置（兼容旧快照）：按旧逻辑走 token 或 flat 结算
        if (snapshot.getInputPricePerMillion() != null && snapshot.getOutputPricePerMillion() != null) {
            return settleWithTokenPricing(preHoldAmount, snapshot, usageData);
        }
        return settleWithFlatPrice(preHoldAmount, snapshot, ruleJson, usageData);
    }
    /**
     * 按 inputTokens × inputPrice/M + outputTokens × outputPrice/M 计算预扣金额。
     */
    private BigDecimal calcPreHoldForTokenPricing(BillingSku sku, SettleRule settleRule, Map<String, Object> params) {
        // 文本入口已按结算策略写入估值；旧调用缺失时保留历史字符换算兜底。
        int inputTokens = safeGetInt(params, "inputTokens",
                BillingConstants.charsToTokens(safeGetInt(params, "inputChars", 0)));
        int outputTokens = safeGetInt(params, "outputTokens",
                BillingConstants.charsToTokens(safeGetInt(params, "estimatedOutputChars", 0)));
        BigDecimal inputPrice = sku.getInputPricePerMillion();
        BigDecimal outputPrice = sku.getOutputPricePerMillion();
        if (settleRule != null && "BUCKETED".equals(normalizeUsagePricingMode(settleRule.getUsagePricingMode()))) {
            // 预冻结阶段尚不知道 token 会落入哪个互斥桶，按同侧最高单价保守冻结；
            // 实际 usage 返回后再按 uncached/cache-read/cache-write 与 visible/reasoning 互斥桶退款。
            inputPrice = maxPrice(inputPrice, sku.getCachedInputPricePerMillion(),
                    sku.getCacheWritePricePerMillion());
            outputPrice = maxPrice(outputPrice, sku.getReasoningPricePerMillion());
        }
        return calcTokenCost(inputTokens, outputTokens, inputPrice, outputPrice);
    }

    private void normalizeTextTokenPreHoldParams(AiModelConfigVo modelConfig, BillingInput billingInput,
                                                  BillingRule rule, MeterType meterType) {
        if (meterType != MeterType.TOKEN || billingInput == null
                || !"TEXT".equalsIgnoreCase(billingInput.getMediaType())
                || billingInput.getParams() == null) {
            return;
        }
        Map<String, Object> params = billingInput.getParams();
        SettleRule settleRule = rule == null ? null : rule.getSettleRule();
        boolean allowExtraCharge = settleRule != null && settleRule.isAllowExtraCharge();
        int conservativeInput = safeGetInt(params, "conservativeInputTokens", 0);
        int balancedInput = safeGetInt(params, "balancedInputTokens", 0);
        if (!allowExtraCharge) {
            if (conservativeInput <= 0) {
                conservativeInput = TextTokenEstimator.estimateUnknownTextConservative(
                        safeGetInt(params, "inputChars", 0), safeGetInt(params, "messageCount", 1));
            }
            params.put("inputTokens", Math.max(safeGetInt(params, "inputTokens", 0), conservativeInput));

            int providerCap = safeGetInt(params, "providerOutputTokenCap", 0);
            if (providerCap <= 0) {
                providerCap = TextOutputLimitResolver.resolveProviderCap(modelConfig, null);
            }
            int ceiling = safeGetInt(params, "billingOutputTokenCeiling",
                    TextOutputLimitResolver.billingCeiling(providerCap));
            int callCount = Math.max(1, safeGetInt(params, "expectedCallCount", 1));
            long requiredOutput = (long) ceiling * callCount;
            int saturatedOutput = (int) Math.min(requiredOutput, Integer.MAX_VALUE);
            params.put("providerOutputTokenCap", providerCap);
            params.put("billingOutputTokenCeiling", ceiling);
            params.put("outputTokens", Math.max(safeGetInt(params, "outputTokens", 0), saturatedOutput));
        } else if (balancedInput > 0) {
            params.put("inputTokens", balancedInput);
        }
    }

    private static BigDecimal maxPrice(BigDecimal first, BigDecimal... candidates) {
        BigDecimal result = first == null ? BigDecimal.ZERO : first;
        if (candidates != null) {
            for (BigDecimal candidate : candidates) {
                if (candidate != null && candidate.compareTo(result) > 0) {
                    result = candidate;
                }
            }
        }
        return result;
    }

    /**
     * 构建分价模式的快照：保存 token 估值和 input/output 价格。
     */
    private BillingSnapshot buildTokenPricingSnapshot(AiModelConfigVo modelConfig, BillingSku sku,
                                                       BillingRule rule, Map<String, Object> params, BigDecimal amount) {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setModelId(modelConfig.getId());
        snapshot.setModelName(modelConfig.getModelName());
        snapshot.setModelType(modelConfig.getModelType());
        snapshot.setBillingMode(BillingConstants.MODE_SKU);
        snapshot.setBillingVersion(modelConfig.getBillingVersion());
        // 保存完整的计费规则JSON：文本结算时需要从中重新解析规则
        snapshot.setBillingRuleJson(modelConfig.getBillingRuleJson());
        snapshot.setSkuCode(sku.getSkuCode());
        snapshot.setSkuName(sku.getSkuName());
        // 保存SKU原始匹配条件（审计用）
        snapshot.setMatchedRuleConditions(sku.getMatch());
        // 保存本次请求的实际参数值
        snapshot.setRequestParams(params);
        snapshot.setPreHoldAmount(amount);
        // 保存分价信息，结算时直接使用
        snapshot.setInputPricePerMillion(sku.getInputPricePerMillion());
        snapshot.setOutputPricePerMillion(sku.getOutputPricePerMillion());
        snapshot.setCachedInputPricePerMillion(sku.getCachedInputPricePerMillion());
        snapshot.setCacheWritePricePerMillion(sku.getCacheWritePricePerMillion());
        snapshot.setReasoningPricePerMillion(sku.getReasoningPricePerMillion());
        snapshot.setUsagePricingMode(rule.getSettleRule() == null
                ? "AGGREGATE" : normalizeUsagePricingMode(rule.getSettleRule().getUsagePricingMode()));
        // 保存最终用于冻结的 token 估值。
        snapshot.setEstimatedInputTokens(safeGetInt(params, "inputTokens",
                BillingConstants.charsToTokens(safeGetInt(params, "inputChars", 0))));
        snapshot.setEstimatedOutputTokens(safeGetInt(params, "outputTokens",
                BillingConstants.charsToTokens(safeGetInt(params, "estimatedOutputChars", 0))));
        snapshot.setProviderOutputTokenCap(safeGetInt(params, "providerOutputTokenCap", 0));
        snapshot.setBillingOutputTokenCeiling(safeGetInt(params, "billingOutputTokenCeiling", 0));
        return snapshot;
    }
    /** 分价结算：按实际单次 token 用量从冻结规则重新匹配 SKU，再使用快照时点价格结算。 */
    private BillingCalcResult settleWithTokenPricing(BigDecimal preHoldAmount, BillingSnapshot snapshot, Map<String, Object> usageData) {
        TokenUsage actualUsage = resolveActualUsage(usageData, snapshot);

        if (!actualUsage.providerUsageCaptured) {
            // Provider 未返回完整 input/output usage 时不能把缺失侧当 0 退款。
            // 无 usage、部分 usage 和仅有估算值均按本次预冻结算，但快照仍保留
            // 已获取侧的真实 token 及完整性标志，便于审计与父聚合识别不完整调用。
            persistTokenUsageAudit(snapshot, actualUsage);
            logTextSettleFallback(snapshot, preHoldAmount, usageData,
                    actualUsage.hasAnyProviderUsage ? "TOKEN_USAGE_PARTIAL" : "TOKEN_USAGE_EMPTY");
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        // 预估 token 与 provider 实际 token 可能跨档，必须按冻结时的完整规则重新命中单次 SKU。
        BillingSku actualSku = resolveActualTokenSku(snapshot, actualUsage);
        BigDecimal inputPrice = snapshot.getInputPricePerMillion();
        BigDecimal outputPrice = snapshot.getOutputPricePerMillion();
        if (Objects.nonNull(actualSku) && Objects.nonNull(actualSku.getInputPricePerMillion())
                && Objects.nonNull(actualSku.getOutputPricePerMillion())) {
            inputPrice = actualSku.getInputPricePerMillion();
            outputPrice = actualSku.getOutputPricePerMillion();
            snapshot.setSkuCode(actualSku.getSkuCode());
            snapshot.setSkuName(actualSku.getSkuName());
            snapshot.setMatchedRuleConditions(actualSku.getMatch());
            snapshot.setInputPricePerMillion(inputPrice);
            snapshot.setOutputPricePerMillion(outputPrice);
            snapshot.setCachedInputPricePerMillion(actualSku.getCachedInputPricePerMillion());
            snapshot.setCacheWritePricePerMillion(actualSku.getCacheWritePricePerMillion());
            snapshot.setReasoningPricePerMillion(actualSku.getReasoningPricePerMillion());
        }

        // 按实际 token × 实际命中档位单价计算（基础金额）+ 输入媒体附加费。
        BigDecimal actualBase = calcTokenCost(actualUsage, snapshot, inputPrice, outputPrice)
                .add(snapshotInputMediaBase(snapshot));
        // 应用与预扣同一份倍率（来自快照），保证审计口径一致
        BigDecimal finalMultiplier = resolveFinalMultiplierFromSnapshot(snapshot);
        BigDecimal actualAmount = actualBase.multiply(finalMultiplier);

        // TOKEN 模式不在此处封顶：actual > preHold 时由 BillingFacadeServiceImpl 统一走补扣逻辑。
        // 此处返回真实计算金额，让上层决定退款/补扣/封顶。

        // 保存实际 token 数用于审计
        persistTokenUsageAudit(snapshot, actualUsage);
        markSettleDone(snapshot, actualAmount, preHoldAmount);

        logTextTokenSettleFormula(snapshot, preHoldAmount, actualBase, actualAmount,
                actualUsage.inputTokens, actualUsage.outputTokens, usageData);

        return BillingCalcResult.sku(snapshot.getSkuCode(), snapshot.getSkuName(), actualAmount, snapshot);
    }

    /** 使用冻结快照里的完整规则匹配实际单次 token 档位，避免结算依赖实时模型价格。 */
    private BillingSku resolveActualTokenSku(BillingSnapshot snapshot, TokenUsage actualUsage) {
        if (Objects.isNull(snapshot) || CharSequenceUtil.isBlank(snapshot.getBillingRuleJson())) {
            return null;
        }
        AiModelConfigVo snapshotConfig = new AiModelConfigVo();
        snapshotConfig.setBillingMode(BillingConstants.MODE_SKU);
        snapshotConfig.setBillingRuleJson(snapshot.getBillingRuleJson());
        snapshotConfig.setModelCode(snapshot.getModelName());
        BillingRule rule = billingRuleResolver.parseRule(snapshotConfig);
        if (Objects.isNull(rule)) {
            return null;
        }
        Map<String, Object> actualParams = Objects.isNull(snapshot.getRequestParams())
                ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshot.getRequestParams());
        actualParams.put("inputTokens", actualUsage.inputTokens);
        actualParams.put("outputTokens", actualUsage.outputTokens);
        actualParams.put("totalTokens", (long) actualUsage.inputTokens + actualUsage.outputTokens);
        BillingSku matched = billingRuleResolver.resolve(rule, actualParams);
        if (Objects.nonNull(matched)) {
            MeterType actualMeterType = resolveFrozenSkuMeterType(rule, matched);
            if (actualMeterType != MeterType.TOKEN) {
                log.error("实际Token命中非TOKEN冻结档位，沿用预冻结档位: model={}, skuCode={}, meterType={}",
                        snapshot.getModelName(), matched.getSkuCode(), actualMeterType);
                return null;
            }
        }
        if (Objects.isNull(matched)) {
            log.error("实际Token未命中冻结计费规则，沿用预冻结档位: model={}, inputTokens={}, outputTokens={}",
                    snapshot.getModelName(), actualUsage.inputTokens, actualUsage.outputTokens);
        }
        return matched;
    }

    private MeterType resolveFrozenSkuMeterType(BillingRule rule, BillingSku sku) {
        String raw = CharSequenceUtil.isNotBlank(sku.getMeterType())
                ? sku.getMeterType() : rule.getMeterType();
        return MeterType.of(raw);
    }
    /**
     * PER_SECOND 结算：pricePerSecond × usageData.actualDuration × finalMultiplier，只退不补。
     * actualDuration 从 usageData 中取，取不到时按预扣金额兜底。
     */
    private BillingCalcResult settleWithPerSecondPricing(BigDecimal preHoldAmount, BillingSnapshot snapshot,
                                                          Map<String, Object> usageData) {
        BigDecimal pps = snapshot.getPricePerSecond();
        if (pps == null || pps.compareTo(BigDecimal.ZERO) <= 0) {
            // 快照中无每秒单价（旧数据兜底），按预扣金额直接结算
            log.info("PER_SECOND结算兜底: pricePerSecond为空, 按预扣金额结算, preHold={}", preHoldAmount);
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        // 取实际时长：优先 usageData.actualDuration，其次 usageData.duration
        int actualDuration = 0;
        if (usageData != null) {
            actualDuration = safeGetInt(usageData, "actualDuration", safeGetInt(usageData, "duration", 0));
        }
        if (actualDuration <= 0) {
            // 无实际时长反馈，按预扣金额结算
            log.info("PER_SECOND结算兜底: actualDuration为0, 按预扣金额结算, preHold={}", preHoldAmount);
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        // 按实际秒数计算基础金额 + 输入媒体附加费（优先使用上游实际输入用量）
        BigDecimal actualBase = pps.multiply(BigDecimal.valueOf(actualDuration))
                .add(resolveSettledInputMediaBase(snapshot, usageData));
        // 应用与预扣同一份倍率
        BigDecimal finalMultiplier = resolveFinalMultiplierFromSnapshot(snapshot);
        BigDecimal actualAmount = actualBase.multiply(finalMultiplier);

        // 只退不补
        if (actualAmount.compareTo(preHoldAmount) > 0) {
            actualAmount = preHoldAmount;
        }

        // 写入审计字段
        snapshot.setActualDurationSeconds(actualDuration);
        markSettleDone(snapshot, actualAmount, preHoldAmount);

        log.info("[结算-PER_SECOND] pricePerSecond={}, expectedDur={}, actualDur={}, baseAmount={}, finalMultiplier={}, actualAmount={}, preHold={}",
                pps, snapshot.getExpectedDurationSeconds(), actualDuration, actualBase, finalMultiplier, actualAmount, preHoldAmount);

        return BillingCalcResult.sku(snapshot.getSkuCode(), snapshot.getSkuName(), actualAmount, snapshot);
    }

    /**
     * 按上游实际输入用量重算输入媒体附加费；缺失实际用量时沿用预冻结快照。
     */
    private BigDecimal resolveSettledInputMediaBase(BillingSnapshot snapshot, Map<String, Object> usageData) {
        if (snapshot == null || usageData == null || usageData.isEmpty()) {
            return snapshotInputMediaBase(snapshot);
        }
        boolean hasImageUsage = usageData.containsKey("actualInputImageCount");
        boolean hasVideoUsage = usageData.containsKey("actualInputVideoSeconds");
        if (!hasImageUsage && !hasVideoUsage) {
            return snapshotInputMediaBase(snapshot);
        }

        BigDecimal imageAmount = snapshot.getInputImageAmount() == null
                ? BigDecimal.ZERO : snapshot.getInputImageAmount();
        if (hasImageUsage && isPositive(snapshot.getInputImageUnitPrice())) {
            int actualCount = Math.max(safeGetInt(usageData, "actualInputImageCount", 0), 0);
            int freeCount = Math.max(snapshot.getInputImageFreeCount() == null
                    ? 0 : snapshot.getInputImageFreeCount(), 0);
            int billedCount = Math.max(actualCount - freeCount, 0);
            imageAmount = snapshot.getInputImageUnitPrice().multiply(BigDecimal.valueOf(billedCount));
            snapshot.setActualInputImageCount(actualCount);
        }

        BigDecimal videoAmount = snapshot.getInputVideoAmount() == null
                ? BigDecimal.ZERO : snapshot.getInputVideoAmount();
        if (hasVideoUsage && isPositive(snapshot.getInputVideoUnitPrice())) {
            int actualSeconds = Math.max(safeGetInt(usageData, "actualInputVideoSeconds", 0), 0);
            videoAmount = snapshot.getInputVideoUnitPrice().multiply(BigDecimal.valueOf(actualSeconds));
            snapshot.setActualInputVideoSeconds(actualSeconds);
        }

        // 旧快照只有聚合金额、没有分项单价时无法安全拆分，继续按原聚合金额结算。
        if (snapshot.getInputImageUnitPrice() == null && snapshot.getInputVideoUnitPrice() == null) {
            return snapshotInputMediaBase(snapshot);
        }
        return imageAmount.add(videoAmount);
    }
    /**
     * 按实际文本用量结算固定价 SKU。
     */
    private BillingCalcResult settleWithFlatPrice(BigDecimal preHoldAmount, BillingSnapshot snapshot, String ruleJson, Map<String, Object> usageData) {
        AiModelConfigVo snapshotConfig = new AiModelConfigVo();
        snapshotConfig.setBillingMode(BillingConstants.MODE_SKU);
        snapshotConfig.setBillingRuleJson(ruleJson);
        snapshotConfig.setModelCode(snapshot.getModelName());

        BillingRule rule = billingRuleResolver.parseRule(snapshotConfig);
        if (rule == null) {
            logTextSettleFallback(snapshot, preHoldAmount, usageData, "RULE_PARSE_FAILED");
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        int actualTotalChars = resolveActualTotalChars(usageData, rule.getSettleRule());
        if (actualTotalChars <= 0) {
            logTextSettleFallback(snapshot, preHoldAmount, usageData, "TOTAL_CHARS_EMPTY");
            markSettleDone(snapshot, preHoldAmount, preHoldAmount);
            return BillingCalcResult.fixed(preHoldAmount, snapshot);
        }

        BillingInput actualInput = new BillingInput("TEXT", Map.of("totalChars", actualTotalChars));
        BillingSku matchedSku = billingRuleResolver.resolve(rule, actualInput.getParams());

        BigDecimal actualBase;
        String skuCode = null;
        String skuName = null;
        if (matchedSku != null) {
            // 旧固定价重匹配 + 输入媒体附加费（与预扣口径一致）
            actualBase = matchedSku.getPrice().add(snapshotInputMediaBase(snapshot));
            skuCode = matchedSku.getSkuCode();
            skuName = matchedSku.getSkuName();
        } else {
            actualBase = preHoldAmount;
        }
        // 应用与预扣同一份倍率（来自快照），保证审计口径一致
        BigDecimal finalMultiplier = resolveFinalMultiplierFromSnapshot(snapshot);
        BigDecimal actualAmount = actualBase.multiply(finalMultiplier);

        // 硬规则：只退不补
        if (actualAmount.compareTo(preHoldAmount) > 0) {
            actualAmount = preHoldAmount;
        }

        markSettleDone(snapshot, actualAmount, preHoldAmount);
        snapshot.setSkuCode(skuCode);
        snapshot.setSkuName(skuName);
        logTextFlatSettleFormula(snapshot, preHoldAmount, actualTotalChars, actualBase, actualAmount, matchedSku, usageData);

        return BillingCalcResult.sku(skuCode, skuName, actualAmount, snapshot);
    }
    /**
     * 结构化 token 用量。
     */
    private static class TokenUsage {
        int inputTokens;
        int outputTokens;
        int uncachedInputTokens;
        int cachedInputTokens;
        int cacheWriteInputTokens;
        int visibleOutputTokens;
        int reasoningTokens;
        boolean hasAnyProviderUsage;
        boolean providerUsageCaptured;
        boolean inputUsageCaptured;
        boolean outputUsageCaptured;
        boolean inputUsageComplete;
        boolean outputUsageComplete;
        boolean inputBucketsComplete;
        boolean outputBucketsComplete;
    }

    /**
     * 从 usageData 解析实际 token 用量。
     * provider 返回的是真实 token 数，不做 charToTokenRatio 转换。
     * 降级路径：从字符估算除以 ratio 转 token。
     */
    private TokenUsage resolveActualUsage(Map<String, Object> usageData, BillingSnapshot snapshot) {
        TokenUsage usage = new TokenUsage();

        if (usageData == null || usageData.isEmpty()) {
            return usage;
        }

        // 第一优先：provider 返回的真实 token 数（不乘 ratio）
        int inputTokens = 0;
        int outputTokens = 0;

        Object inputTokensObj = usageData.get("input_tokens");
        if (inputTokensObj != null) {
            inputTokens = toInt(inputTokensObj);
        }
        Object outputTokensObj = usageData.get("output_tokens");
        if (outputTokensObj != null) {
            outputTokens = toInt(outputTokensObj);
        }
        // 兼容 prompt_tokens / completion_tokens 字段名
        if (inputTokens == 0 && usageData.get("prompt_tokens") != null) {
            inputTokens = toInt(usageData.get("prompt_tokens"));
        }
        if (outputTokens == 0 && usageData.get("completion_tokens") != null) {
            outputTokens = toInt(usageData.get("completion_tokens"));
        }

        boolean hasInputParent = usageData.containsKey("input_tokens")
                || usageData.containsKey("prompt_tokens");
        boolean hasOutputParent = usageData.containsKey("output_tokens")
                || usageData.containsKey("completion_tokens");
        if (hasInputParent || hasOutputParent) {
            usage.inputTokens = inputTokens;
            usage.outputTokens = outputTokens;
            usage.hasAnyProviderUsage = true;
            usage.inputUsageCaptured = hasInputParent;
            usage.outputUsageCaptured = hasOutputParent;
            usage.inputUsageComplete = hasInputParent && Boolean.parseBoolean(String.valueOf(
                    usageData.getOrDefault("input_usage_complete", true)));
            usage.outputUsageComplete = hasOutputParent && Boolean.parseBoolean(String.valueOf(
                    usageData.getOrDefault("output_usage_complete", true)));
            usage.cachedInputTokens = nonNegativeInt(usageData, "cached_input_tokens",
                    nonNegativeInt(usageData, "cache_read_input_tokens", 0));
            usage.cacheWriteInputTokens = nonNegativeInt(usageData, "cache_write_input_tokens", 0);
            usage.uncachedInputTokens = nonNegativeInt(usageData, "uncached_input_tokens",
                    Math.max(inputTokens - usage.cachedInputTokens - usage.cacheWriteInputTokens, 0));
            usage.reasoningTokens = nonNegativeInt(usageData, "reasoning_tokens", 0);
            usage.visibleOutputTokens = nonNegativeInt(usageData, "visible_output_tokens",
                    Math.max(outputTokens - usage.reasoningTokens, 0));
            boolean providerMarkedComplete = Boolean.parseBoolean(String.valueOf(
                    usageData.getOrDefault("provider_usage_captured",
                            hasInputParent && hasOutputParent)));
            usage.providerUsageCaptured = usage.inputUsageComplete && usage.outputUsageComplete
                    && providerMarkedComplete;
            usage.inputBucketsComplete = Boolean.parseBoolean(String.valueOf(
                    usageData.getOrDefault("input_token_buckets_complete", false)));
            usage.outputBucketsComplete = Boolean.parseBoolean(String.valueOf(
                    usageData.getOrDefault("output_token_buckets_complete", false)));
            return usage;
        }

        // 仅返回 total/bucket 等真实字段也说明 Provider 已提供部分 usage，
        // 但无法安全重建 input/output 双侧，因此只做审计标记并保守结算。
        usage.hasAnyProviderUsage = ProviderUsageSupport.hasAnyProviderUsage(usageData);

        // 第二优先：token 估算值（已由 facade 层除过 ratio）
        Object inputEstimate = usageData.get("input_tokens_estimate");
        Object outputEstimate = usageData.get("output_tokens_estimate");
        if (inputEstimate != null || outputEstimate != null) {
            usage.inputTokens = inputEstimate != null ? toInt(inputEstimate) : 0;
            usage.outputTokens = outputEstimate != null ? toInt(outputEstimate) : 0;
            usage.uncachedInputTokens = usage.inputTokens;
            usage.visibleOutputTokens = usage.outputTokens;
            return usage;
        }

        // 第三优先：历史调用仅返回字符数时沿用旧换算兜底。
        Object totalCharsEstimate = usageData.get("total_chars_estimate");
        if (totalCharsEstimate != null) {
            int totalTokens = BillingConstants.charsToTokens(toInt(totalCharsEstimate));
            // 无法区分 input/output，按快照中的预估值比例分配
            Integer estInput = snapshot.getEstimatedInputTokens();
            Integer estOutput = snapshot.getEstimatedOutputTokens();
            if (estInput != null && estOutput != null && estInput + estOutput > 0) {
                double inputRatio = (double) estInput / (estInput + estOutput);
                usage.inputTokens = (int) Math.round(totalTokens * inputRatio);
                usage.outputTokens = totalTokens - usage.inputTokens;
            } else {
                // 默认 70% input, 30% output
                usage.inputTokens = (int) Math.round(totalTokens * 0.7);
                usage.outputTokens = totalTokens - usage.inputTokens;
            }
            usage.uncachedInputTokens = usage.inputTokens;
            usage.visibleOutputTokens = usage.outputTokens;
        }

        return usage;
    }

    private void persistTokenUsageAudit(BillingSnapshot snapshot, TokenUsage usage) {
        snapshot.setActualInputTokens(usage.inputUsageCaptured ? usage.inputTokens : null);
        snapshot.setActualOutputTokens(usage.outputUsageCaptured ? usage.outputTokens : null);
        snapshot.setActualUncachedInputTokens(usage.inputUsageCaptured
                ? usage.uncachedInputTokens : null);
        snapshot.setActualCachedInputTokens(usage.inputUsageCaptured
                ? usage.cachedInputTokens : null);
        snapshot.setActualCacheWriteInputTokens(usage.inputUsageCaptured
                ? usage.cacheWriteInputTokens : null);
        snapshot.setActualVisibleOutputTokens(usage.outputUsageCaptured
                ? usage.visibleOutputTokens : null);
        snapshot.setActualReasoningTokens(usage.outputUsageCaptured
                ? usage.reasoningTokens : null);
        snapshot.setProviderUsageCaptured(usage.providerUsageCaptured);
        snapshot.setHasAnyProviderUsage(usage.hasAnyProviderUsage);
        snapshot.setInputUsageComplete(usage.inputUsageComplete);
        snapshot.setOutputUsageComplete(usage.outputUsageComplete);
        snapshot.setInputTokenBucketsComplete(usage.inputUsageComplete
                && usage.inputBucketsComplete);
        snapshot.setOutputTokenBucketsComplete(usage.outputUsageComplete
                && usage.outputBucketsComplete);
    }

    /**
     * 旧模式：从 usage 解析 totalChars（向后兼容）。
     */
    private int resolveActualTotalChars(Map<String, Object> usageData, SettleRule settleRule) {
        int ratio = resolveCharToTokenRatio(settleRule);

        if (usageData == null || usageData.isEmpty()) {
            return 0;
        }

        // 第一优先：上游 provider 返回的 token usage（转成字符用于旧 SKU 匹配）
        Object totalTokens = usageData.get("total_tokens");
        if (totalTokens != null) {
            return toInt(totalTokens) * ratio;
        }
        int input = usageData.get("input_tokens") != null ? toInt(usageData.get("input_tokens")) : 0;
        int output = usageData.get("output_tokens") != null ? toInt(usageData.get("output_tokens")) : 0;
        if (input + output > 0) {
            return (input + output) * ratio;
        }

        // 第二优先：字符估算
        Object totalCharsEstimate = usageData.get("total_chars_estimate");
        if (totalCharsEstimate != null) {
            return toInt(totalCharsEstimate);
        }

        return 0;
    }
    /**
     * 按 token 用量 × 单价计算费用。
     * cost = input × inputPrice/M + output × outputPrice/M
     */
    private BigDecimal calcTokenCost(int inputTokens, int outputTokens,
                                      BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        BigDecimal inputCost = new BigDecimal(inputTokens)
                .multiply(inputPricePerMillion)
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal outputCost = new BigDecimal(outputTokens)
                .multiply(outputPricePerMillion)
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }

    /** 按聚合总量或互斥子桶计算，父级与子桶永不叠加。 */
    private BigDecimal calcTokenCost(TokenUsage usage, BillingSnapshot snapshot,
                                     BigDecimal inputPrice, BigDecimal outputPrice) {
        if (!"BUCKETED".equals(normalizeUsagePricingMode(snapshot.getUsagePricingMode()))) {
            return calcTokenCost(usage.inputTokens, usage.outputTokens, inputPrice, outputPrice);
        }
        BigDecimal cacheReadPrice = Objects.requireNonNullElse(snapshot.getCachedInputPricePerMillion(), inputPrice);
        BigDecimal cacheWritePrice = Objects.requireNonNullElse(snapshot.getCacheWritePricePerMillion(), inputPrice);
        BigDecimal reasoningPrice = Objects.requireNonNullElse(snapshot.getReasoningPricePerMillion(), outputPrice);
        BigDecimal inputCost;
        long inputBucketSum = (long) usage.uncachedInputTokens + usage.cachedInputTokens
                + usage.cacheWriteInputTokens;
        if (!usage.inputBucketsComplete || inputBucketSum > usage.inputTokens) {
            inputCost = calcSingleTokenCost(usage.inputTokens,
                    maxPrice(inputPrice, cacheReadPrice, cacheWritePrice));
        } else {
            int remaining = Math.max(usage.inputTokens - (int) inputBucketSum, 0);
            inputCost = calcSingleTokenCost(usage.uncachedInputTokens, inputPrice)
                    .add(calcSingleTokenCost(usage.cachedInputTokens, cacheReadPrice))
                    .add(calcSingleTokenCost(usage.cacheWriteInputTokens, cacheWritePrice))
                    .add(calcSingleTokenCost(remaining,
                            maxPrice(inputPrice, cacheReadPrice, cacheWritePrice)));
        }

        BigDecimal outputCost;
        long outputBucketSum = (long) usage.visibleOutputTokens + usage.reasoningTokens;
        if (!usage.outputBucketsComplete || outputBucketSum > usage.outputTokens) {
            outputCost = calcSingleTokenCost(usage.outputTokens, maxPrice(outputPrice, reasoningPrice));
        } else {
            int remaining = Math.max(usage.outputTokens - (int) outputBucketSum, 0);
            outputCost = calcSingleTokenCost(usage.visibleOutputTokens, outputPrice)
                    .add(calcSingleTokenCost(usage.reasoningTokens, reasoningPrice))
                    .add(calcSingleTokenCost(remaining, maxPrice(outputPrice, reasoningPrice)));
        }
        return inputCost.add(outputCost);
    }

    private String normalizeUsagePricingMode(String value) {
        return "BUCKETED".equalsIgnoreCase(value) ? "BUCKETED" : "AGGREGATE";
    }

    private int nonNegativeInt(Map<String, Object> data, String key, int fallback) {
        Object value = data == null ? null : data.get(key);
        return value == null ? Math.max(fallback, 0) : Math.max(toInt(value), 0);
    }

    /**
     * 仅供日志使用：从 snapshot.requestParams 取 size/resolution。
     */
    private Object resolveSnapshotSizeForLog(BillingSnapshot snapshot) {
        if (snapshot == null || snapshot.getRequestParams() == null) {
            return null;
        }
        Object size = snapshot.getRequestParams().get("size");
        return size != null ? size : snapshot.getRequestParams().get("resolution");
    }

    /**
     * 统一预冻结公式日志：按 meterType 打印对应公式明细。
     */
    private void logUnifiedPreHoldFormula(AiModelConfigVo modelConfig, BillingSnapshot snapshot,
                                          MeterType meterType, BigDecimal baseAmount, BigDecimal adjustedAmount) {
        BigDecimal modelMultiplier = defaultMultiplier(snapshot == null ? null : snapshot.getModelBillingMultiplier());
        BigDecimal globalMultiplier = defaultMultiplier(snapshot == null ? null : snapshot.getGlobalBillingMultiplier());
        BigDecimal finalMultiplier = defaultMultiplier(snapshot == null ? null : snapshot.getFinalBillingMultiplier());
        log.info("[预冻结] meterType={}, modelCode={}, modelName={}, billingMode={}, skuCode={}, skuName={}",
                meterType, modelConfig.getModelCode(), modelConfig.getModelName(),
                snapshot == null ? null : snapshot.getBillingMode(),
                snapshot == null ? null : snapshot.getSkuCode(),
                snapshot == null ? null : snapshot.getSkuName());
        // 按 meterType 打印具体公式
        switch (meterType) {
            case TOKEN -> {
                int inputTokens = snapshot.getEstimatedInputTokens() == null ? 0 : snapshot.getEstimatedInputTokens();
                int outputTokens = snapshot.getEstimatedOutputTokens() == null ? 0 : snapshot.getEstimatedOutputTokens();
                BigDecimal inputPrice = defaultDecimal(snapshot.getInputPricePerMillion());
                BigDecimal outputPrice = defaultDecimal(snapshot.getOutputPricePerMillion());
                BigDecimal inputCost = calcSingleTokenCost(inputTokens, inputPrice);
                BigDecimal outputCost = calcSingleTokenCost(outputTokens, outputPrice);
                // 同时记录原始字符数，便于核对入口估值与最终冻结量。
                int inputChars = snapshot.getRequestParams() != null ? safeGetInt(snapshot.getRequestParams(), "inputChars", 0) : 0;
                int outputChars = snapshot.getRequestParams() != null ? safeGetInt(snapshot.getRequestParams(), "estimatedOutputChars", 0) : 0;
                log.info("[预冻结-TOKEN] inputChars={}, frozenInputTokens={}", inputChars, inputTokens);
                log.info("[预冻结-TOKEN] estimatedOutputChars={}, frozenOutputTokens={}", outputChars, outputTokens);
                log.info("[预冻结-TOKEN] inputTokens={} * inputPrice={}/M = {}", inputTokens, inputPrice, inputCost);
                log.info("[预冻结-TOKEN] outputTokens={} * outputPrice={}/M = {}", outputTokens, outputPrice, outputCost);
                log.info("[预冻结-TOKEN] baseAmount={}", baseAmount);
            }
            case PER_IMAGE -> {
                BigDecimal unitPrice = snapshot == null ? BigDecimal.ZERO : defaultDecimal(snapshot.getUnitPrice());
                int count = snapshot != null && snapshot.getExpectedImageCount() != null ? snapshot.getExpectedImageCount() : 1;
                log.info("[预冻结-PER_IMAGE] unitPrice={} * expectedImageCount={} = {}, generateMode={}, size={}",
                        unitPrice, count, baseAmount, snapshot == null ? null : snapshot.getGenerateMode(),
                        resolveSnapshotSizeForLog(snapshot));
            }
            case PER_SECOND -> {
                BigDecimal pps = snapshot != null && snapshot.getPricePerSecond() != null ? snapshot.getPricePerSecond() : BigDecimal.ZERO;
                int dur = snapshot != null && snapshot.getExpectedDurationSeconds() != null ? snapshot.getExpectedDurationSeconds() : 0;
                log.info("[预冻结-PER_SECOND] skuTotalPrice={}, pricePerSecond={}, durationSeconds={}, resolution={}",
                        baseAmount, pps, dur, resolveSnapshotSizeForLog(snapshot));
            }
            case SKU_PACKAGE -> {
                log.info("[预冻结-SKU_PACKAGE] skuPackagePrice={}, resolution={}",
                        baseAmount, resolveSnapshotSizeForLog(snapshot));
            }
            case PER_CHAR -> {
                BigDecimal unitPrice = snapshot == null ? BigDecimal.ZERO : defaultDecimal(snapshot.getUnitPrice());
                int chars = snapshot != null && snapshot.getRequestParams() != null
                        ? safeGetInt(snapshot.getRequestParams(), "chars", 0) : 0;
                log.info("[预冻结-PER_CHAR] pricePerChar={} * chars={} = {}", unitPrice, chars, baseAmount);
            }
        }
        log.info("[预冻结] 官方原价={}元, globalMultiplier={}积分/元, modelMultiplier={}, finalMultiplier={}, preHoldCredits={}",
                baseAmount, globalMultiplier, modelMultiplier, finalMultiplier, adjustedAmount);
    }

    private void logTextTokenSettleFormula(BillingSnapshot snapshot, BigDecimal preHoldAmount,
                                           BigDecimal actualBase, BigDecimal actualAmount,
                                           int inputTokens, int outputTokens, Map<String, Object> usageData) {
        BigDecimal inputPrice = defaultDecimal(snapshot.getInputPricePerMillion());
        BigDecimal outputPrice = defaultDecimal(snapshot.getOutputPricePerMillion());
        BigDecimal inputCost = calcSingleTokenCost(inputTokens, inputPrice);
        BigDecimal outputCost = calcSingleTokenCost(outputTokens, outputPrice);
        BigDecimal modelMultiplier = defaultMultiplier(snapshot.getModelBillingMultiplier());
        BigDecimal globalMultiplier = defaultMultiplier(snapshot.getGlobalBillingMultiplier());
        BigDecimal finalMultiplier = defaultMultiplier(snapshot.getFinalBillingMultiplier());
        BigDecimal rawActualAmount = actualBase.multiply(finalMultiplier);
        String usageSource = resolveUsageSource(usageData);
        log.info("文本LLM结算费用明细: modelName={}, billingMode={}, skuCode={}, skuName={}, usageSource={}",
                snapshot.getModelName(), snapshot.getBillingMode(), snapshot.getSkuCode(), snapshot.getSkuName(), usageSource);
        log.info("文本LLM结算公式: inputTokens={}, inputPricePerMillion={}, inputCost={} * {} / 1000000 = {}",
                inputTokens, inputPrice, inputTokens, inputPrice, inputCost);
        log.info("文本LLM结算公式: outputTokens={}, outputPricePerMillion={}, outputCost={} * {} / 1000000 = {}",
                outputTokens, outputPrice, outputTokens, outputPrice, outputCost);
        log.info("文本LLM结算公式: baseAmount={} + {} = {}", inputCost, outputCost, actualBase);
        log.info("文本LLM结算公式: modelMultiplier={}, globalMultiplier={}, finalMultiplier={} = {} * {}",
                modelMultiplier, globalMultiplier, finalMultiplier, modelMultiplier, globalMultiplier);
        log.info("文本LLM结算结果: calculatedCredits={} = actualBaseYuan({}) * finalMultiplier({}), preHoldCredits={}, finalActualCredits={}",
                rawActualAmount, actualBase, finalMultiplier, preHoldAmount, actualAmount);
    }

    private void logTextFlatSettleFormula(BillingSnapshot snapshot, BigDecimal preHoldAmount, int actualTotalChars,
                                          BigDecimal actualBase, BigDecimal actualAmount,
                                          BillingSku matchedSku, Map<String, Object> usageData) {
        BigDecimal modelMultiplier = defaultMultiplier(snapshot.getModelBillingMultiplier());
        BigDecimal globalMultiplier = defaultMultiplier(snapshot.getGlobalBillingMultiplier());
        BigDecimal finalMultiplier = defaultMultiplier(snapshot.getFinalBillingMultiplier());
        BigDecimal rawActualAmount = actualBase.multiply(finalMultiplier);
        String usageSource = resolveUsageSource(usageData);
        log.info("文本LLM结算费用明细: modelName={}, billingMode={}, skuCode={}, skuName={}, usageSource={}",
                snapshot.getModelName(), snapshot.getBillingMode(), snapshot.getSkuCode(), snapshot.getSkuName(), usageSource);
        log.info("文本LLM结算公式: actualTotalChars={}, matchedSkuCode={}, matchedSkuName={}, matchedSkuPrice={}",
                actualTotalChars, matchedSku == null ? null : matchedSku.getSkuCode(),
                matchedSku == null ? null : matchedSku.getSkuName(), actualBase);
        log.info("文本LLM结算公式: baseAmount={} (旧SKU固定价重匹配结果)", actualBase);
        log.info("文本LLM结算公式: modelMultiplier={}, globalMultiplier={}, finalMultiplier={} = {} * {}",
                modelMultiplier, globalMultiplier, finalMultiplier, modelMultiplier, globalMultiplier);
        log.info("文本LLM结算结果: calculatedCredits={} = actualBaseYuan({}) * finalMultiplier({}), preHoldCredits={}, finalActualCredits={}",
                rawActualAmount, actualBase, finalMultiplier, preHoldAmount, actualAmount);
    }

    private void logTextSettleFallback(BillingSnapshot snapshot, BigDecimal preHoldAmount,
                                       Map<String, Object> usageData, String reason) {
        log.info("文本LLM结算降级: modelName={}, billingMode={}, skuCode={}, skuName={}, reason={}, usageSource={}, finalActualCredits={} (按预冻结积分结算)",
                snapshot == null ? null : snapshot.getModelName(),
                snapshot == null ? null : snapshot.getBillingMode(),
                snapshot == null ? null : snapshot.getSkuCode(),
                snapshot == null ? null : snapshot.getSkuName(),
                reason, resolveUsageSource(usageData), preHoldAmount);
    }

    private String resolveUsageSource(Map<String, Object> usageData) {
        if (usageData == null || usageData.isEmpty()) {
            return "EMPTY";
        }
        if (usageData.get("input_tokens") != null || usageData.get("output_tokens") != null
                || usageData.get("prompt_tokens") != null || usageData.get("completion_tokens") != null) {
            return "PROVIDER_REAL_USAGE";
        }
        if (usageData.get("input_tokens_estimate") != null || usageData.get("output_tokens_estimate") != null) {
            return "TOKEN_ESTIMATE";
        }
        if (usageData.get("total_chars_estimate") != null || usageData.get("output_chars_estimate") != null) {
            return "CHAR_ESTIMATE";
        }
        return "UNKNOWN";
    }

    private BigDecimal calcSingleTokenCost(int tokens, BigDecimal pricePerMillion) {
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal defaultMultiplier(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : value;
    }

    /**
     * 统一标记结算终态：设置 actualAmount、refundAmount、textSettleDone。
     */
    private void markSettleDone(BillingSnapshot snapshot, BigDecimal actualAmount, BigDecimal preHoldAmount) {
        snapshot.setActualAmount(actualAmount);
        snapshot.setRefundAmount(preHoldAmount.subtract(actualAmount));
        snapshot.setTextSettleDone(true);
    }

    private int resolveCharToTokenRatio(SettleRule settleRule) {
        if (settleRule != null && settleRule.getCharToTokenRatio() > 0) {
            return settleRule.getCharToTokenRatio();
        }
        return BillingConstants.DEFAULT_CHAR_TO_TOKEN_RATIO;
    }

    private int resolveCharToTokenRatioFromSnapshot(BillingSnapshot snapshot) {
        // 从快照的 billingRuleJson 解析 settleRule.charToTokenRatio
        if (snapshot != null && snapshot.getBillingRuleJson() != null) {
            try {
                BillingRule rule = billingRuleResolver.parseRule(
                        new AiModelConfigVo() {{
                            setBillingMode(BillingConstants.MODE_SKU);
                            setBillingRuleJson(snapshot.getBillingRuleJson());
                        }});
                if (rule != null && rule.getSettleRule() != null && rule.getSettleRule().getCharToTokenRatio() > 0) {
                    return rule.getSettleRule().getCharToTokenRatio();
                }
            } catch (Exception e) {
                log.warn("从快照解析charToTokenRatio失败，使用默认值", e);
            }
        }
        return BillingConstants.DEFAULT_CHAR_TO_TOKEN_RATIO;
    }

    private BillingSnapshot buildFixedSnapshot(AiModelConfigVo modelConfig, BigDecimal amount) {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setModelId(modelConfig.getId());
        snapshot.setModelName(modelConfig.getModelName());
        snapshot.setModelType(modelConfig.getModelType());
        snapshot.setBillingMode(BillingConstants.MODE_FIXED);
        snapshot.setBillingVersion(modelConfig.getBillingVersion());
        snapshot.setPreHoldAmount(amount);
        return snapshot;
    }

    private BillingSnapshot buildSkuSnapshot(AiModelConfigVo modelConfig, BillingSku sku, BillingRule rule, Map<String, Object> requestParams) {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setModelId(modelConfig.getId());
        snapshot.setModelName(modelConfig.getModelName());
        snapshot.setModelType(modelConfig.getModelType());
        snapshot.setBillingMode(BillingConstants.MODE_SKU);
        snapshot.setBillingVersion(modelConfig.getBillingVersion());
        snapshot.setBillingRuleJson(modelConfig.getBillingRuleJson());
        snapshot.setSkuCode(sku.getSkuCode());
        snapshot.setSkuName(sku.getSkuName());
        snapshot.setMatchedRuleConditions(sku.getMatch());
        snapshot.setRequestParams(requestParams);
        snapshot.setPreHoldAmount(sku.getPrice());
        return snapshot;
    }

    private BillingSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONUtil.toBean(json, BillingSnapshot.class);
        } catch (Exception e) {
            log.error("计费快照JSON解析失败", e);
            return null;
        }
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int safeGetInt(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object val = params.get(key);
        if (val == null) {
            return defaultValue;
        }
        int result = toInt(val);
        return result > 0 ? result : defaultValue;
    }

    /**
     * 向上取整整数除法：ceil(a / b)。
     */
    private static int ceilDiv(int a, int b) {
        if (b <= 0) {
            return a;
        }
        return (a + b - 1) / b;
    }
}
