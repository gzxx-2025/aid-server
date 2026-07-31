package com.aid.billing.estimate;

import cn.hutool.core.text.CharSequenceUtil;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingRule;
import com.aid.billing.service.BillingRuleResolver;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 预冻结参数估算分发器：根据 meterType 选择对应的估算策略，对 BillingInput 进行参数增补。
 * 调用时机：BillingFacadeServiceImpl.prepareBilling() 中，
 * 在 BillingInputExtractor 提取原始业务参数之后、BillingAmountCalculator 计算预扣金额之前。
 */
@Slf4j
@Component
public class BillingEstimateResolver {

    private final BillingRuleResolver billingRuleResolver;
    private final Map<MeterType, BillingEstimateStrategy> strategies;

    public BillingEstimateResolver(BillingRuleResolver billingRuleResolver,
                                   TokenBillingEstimateStrategy tokenStrategy,
                                   PerImageBillingEstimateStrategy perImageStrategy,
                                   PerSecondBillingEstimateStrategy perSecondStrategy,
                                   SkuPackageBillingEstimateStrategy skuPackageStrategy) {
        this.billingRuleResolver = billingRuleResolver;
        this.strategies = new EnumMap<>(MeterType.class);
        this.strategies.put(MeterType.TOKEN, tokenStrategy);
        this.strategies.put(MeterType.PER_IMAGE, perImageStrategy);
        this.strategies.put(MeterType.PER_SECOND, perSecondStrategy);
        this.strategies.put(MeterType.SKU_PACKAGE, skuPackageStrategy);
    }

    /**
     * 按 meterType 分发预冻结参数估算。
     *
     * @param billingInput 已提取原始业务参数的计费输入
     * @param modelConfig  模型配置
     */
    public void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig) {
        if (billingInput == null || modelConfig == null) {
            return;
        }
        MeterType meterType = resolveMeterType(modelConfig);
        BillingEstimateStrategy strategy = strategies.get(meterType);
        if (strategy != null) {
            log.info("预冻结估算分发: modelCode={}, meterType={}, strategy={}",
                    modelConfig.getModelCode(), meterType, strategy.getClass().getSimpleName());
            strategy.enrichEstimate(billingInput, modelConfig);
        }
    }

    /**
     * 从模型配置中解析 meterType：
     * 1) 优先 billingRuleJson.meterType
     * 2) 对 IMAGE 模型：识别已知 TOKEN 图片模型族（Gemini / gpt-image*），避免误落 PER_IMAGE
     * 3) 其余按 modelType 推断
     */
    private MeterType resolveMeterType(AiModelConfigVo modelConfig) {
        String modelCode = modelConfig.getModelCode();
        // 优先从 billingRuleJson 解析
        if (CharSequenceUtil.isNotBlank(modelConfig.getBillingRuleJson())) {
            try {
                BillingRule rule = billingRuleResolver.parseRule(modelConfig);
                if (rule != null && CharSequenceUtil.isNotBlank(rule.getMeterType())) {
                    MeterType mt = MeterType.of(rule.getMeterType());
                    if (mt != null) {
                        return mt;
                    }
                }
            } catch (Exception e) {
                log.warn("从billingRuleJson解析meterType失败, modelCode={}", modelCode);
            }
        }

        // 兜底：按 modelType + 已知 TOKEN 图片模型族 推断
        String modelType = modelConfig.getModelType();
        if ("TEXT".equalsIgnoreCase(modelType)) {
            log.warn("meterType未配置, 按modelType=TEXT兜底为TOKEN, modelCode={}", modelCode);
            return MeterType.TOKEN;
        } else if ("IMAGE".equalsIgnoreCase(modelType)) {
            // 识别已知的 TOKEN 图片模型族，避免误落 PER_IMAGE
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

    /**
     * 判断是否为已知的 TOKEN 计费图片模型族。
     * 新增 TOKEN 图片模型时在此追加匹配规则即可，无需改主流程。
     *
     *   - Gemini 系列：modelCode 含 "gemini" 且含 "image"
     *   - GPT-Image 系列：modelCode 含 "gpt" 且含 "image"（预留）
     *
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
}
