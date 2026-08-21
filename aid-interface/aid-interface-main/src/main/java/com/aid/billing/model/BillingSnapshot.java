package com.aid.billing.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 计费快照：写入 aid_media_task.billing_snapshot_json，
 * 包含任务创建时的完整计费信息，防止后续改价影响历史任务。
 */
@Data
public class BillingSnapshot {

    /** 模型ID */
    private Long modelId;

    /** 模型名称 */
    private String modelName;

    /** 模型类型：TEXT / IMAGE / VIDEO */
    private String modelType;

    /** 统一计费口径：TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE */
    private String meterType;

    /** 计费模式：FIXED / SKU */
    private String billingMode;

    /** 计费规则版本号 */
    private Integer billingVersion;

    /** 本次任务创建时是否免费；历史快照缺失时按收费处理 */
    private Boolean isFree;

    /** 完整计费规则JSON（文本结算重算时使用，避免依赖实时模型配置） */
    private String billingRuleJson;

    /** 命中的SKU编码（SKU模式） */
    private String skuCode;

    /** 命中的SKU名称（SKU模式） */
    private String skuName;

    /** 命中的SKU原始匹配条件（来自sku.match，用于审计"为什么命中这条SKU"） */
    private Map<String, Object> matchedRuleConditions;

    /** 本次请求实际传入的计费参数（如 {resolution:"720P", duration:5}） */
    private Map<String, Object> requestParams;

    /** 预估输入Token数（预扣时由chars估算） */
    private Integer estimatedInputTokens;

    /** 预估输出Token数（预扣时由chars估算） */
    private Integer estimatedOutputTokens;

    /** 实际下发给文本供应商的输出硬上限。 */
    private Integer providerOutputTokenCap;

    /** 供应商上限加文档声明的少量越界保护。 */
    private Integer billingOutputTokenCeiling;

    /** 实际输入Token数（结算时从provider usage获取） */
    private Integer actualInputTokens;

    /** 实际输出Token数（结算时从provider usage获取） */
    private Integer actualOutputTokens;

    /** 实际未缓存输入Token。 */
    private Integer actualUncachedInputTokens;

    /** 实际缓存读取输入Token。 */
    private Integer actualCachedInputTokens;

    /** 实际缓存写入输入Token。 */
    private Integer actualCacheWriteInputTokens;

    /** 实际可见正文输出Token。 */
    private Integer actualVisibleOutputTokens;

    /** 实际思考Token；通常是 actualOutputTokens 的子集。 */
    private Integer actualReasoningTokens;

    /** 是否捕获供应商真实Usage。 */
    private Boolean providerUsageCaptured;

    /** 供应商是否返回任一 token 用量字段。 */
    private Boolean hasAnyProviderUsage;

    /** 供应商输入父级 token 是否完整。 */
    private Boolean inputUsageComplete;

    /** 供应商输出父级 token 是否完整。 */
    private Boolean outputUsageComplete;

    /** 供应商输入 token 子桶明细是否完整且自洽。 */
    private Boolean inputTokenBucketsComplete;

    /** 供应商输出 token 子桶明细是否完整且自洽。 */
    private Boolean outputTokenBucketsComplete;

    /** 输入官方原价（元/百万Token），快照时点值 */
    private BigDecimal inputPricePerMillion;

    /** 输出官方原价（元/百万Token），快照时点值 */
    private BigDecimal outputPricePerMillion;

    /** 缓存读取输入价格快照。 */
    private BigDecimal cachedInputPricePerMillion;

    /** 缓存写入输入价格快照。 */
    private BigDecimal cacheWritePricePerMillion;

    /** 思考Token价格快照。 */
    private BigDecimal reasoningPricePerMillion;

    /** Token计费口径快照：AGGREGATE / BUCKETED。 */
    private String usagePricingMode;

    /** 预扣积分 */
    private BigDecimal preHoldAmount;

    /** 实际扣费积分（结算后回填） */
    private BigDecimal actualAmount;

    /** 退款积分（结算后回填） */
    private BigDecimal refundAmount;

    /** 结算时间（yyyy-MM-dd HH:mm:ss） */
    private String settleTime;

    /** 文本结算是否已完成（审计标记，并发控制由DB字段text_settle_status负责） */
    private boolean textSettleDone;

    /** 模型级计费倍率快照（来自 aid_ai_model.billing_multiplier，默认 1.00） */
    private BigDecimal modelBillingMultiplier;

    /** 模型基础倍率快照（积分/元，来自 aid_config，默认 100） */
    private BigDecimal globalBillingMultiplier;

    /** 最终综合倍率快照 = modelBillingMultiplier × globalBillingMultiplier */
    private BigDecimal finalBillingMultiplier;

    /** 官方原价金额快照（元，未乘任何倍率前，审计用） */
    private BigDecimal baseAmount;
    /** 应补扣积分：actual > preHold 时的差额（extra = actual - preHold） */
    private BigDecimal extraChargeRequired;

    /** 实际补扣成功积分（余额不足时 < extraChargeRequired） */
    private BigDecimal extraChargeActual;

    /** 是否发生部分补扣（余额不足只能补扣部分） */
    private Boolean partialExtraCharge;
    /**
     * 图片预扣时使用的目标输出张数（仅预扣依据，非结算依据）。
     */
    private Integer expectedImageCount;

    /**
     * 图片结算时 provider 实际返回的图片张数（最终结算依据）。
     */
    private Integer actualImageCount;

    /**
     * 图片单张官方原价快照（元）：避免后续改价影响历史任务。
     * FIXED 模式来自 modelConfig.costCredits，SKU 模式来自 matchedSku.price。
     */
    private BigDecimal unitPrice;

    /**
     * 单价类型：当前固定 {@code PER_IMAGE}，预留未来按面积/分辨率阶梯。
     */
    private String unitPriceType;

    /**
     * 最终按该张数结算（通常等于 actualImageCount，取保护上限后值）。
     */
    private Integer settledImageCount;

    /**
     * 图片生成模式快照：{@code TEXT_TO_IMAGE} / {@code IMAGE_EDIT} / {@code UPSCALE}，用于审计 SKU 命中。
     */
    private String generateMode;
    /** 输入媒体预冻结附加费基础额（参考图按张 + 输入视频按秒，未乘倍率；上游回传用量时按快照单价重算） */
    private BigDecimal inputMediaAmount;

    /** 输入图片附加费基础额快照（未乘倍率） */
    private BigDecimal inputImageAmount;

    /** 输入视频附加费基础额快照（未乘倍率） */
    private BigDecimal inputVideoAmount;

    /** 输入图片官方单价快照（元/张） */
    private BigDecimal inputImageUnitPrice;

    /** 输入图片免费张数快照 */
    private Integer inputImageFreeCount;

    /** 输入视频官方单价快照（元/秒） */
    private BigDecimal inputVideoUnitPrice;

    /** 输入图片收费张数快照（已按 maxCount 截断总数并扣除 freeCount） */
    private Integer billedInputImageCount;

    /** 上游回传的实际输入图片总数 */
    private Integer actualInputImageCount;

    /** 输入视频计费秒数快照（已按 inputPricing.video.maxSeconds 截断） */
    private Integer billedInputVideoSeconds;

    /** 上游回传的实际输入视频秒数 */
    private Integer actualInputVideoSeconds;
    /** 每秒单价快照（PER_SECOND 计费口径使用） */
    private BigDecimal pricePerSecond;

    /** 预扣时的预计时长秒数 */
    private Integer expectedDurationSeconds;

    /** 结算时的实际时长秒数 */
    private Integer actualDurationSeconds;
    /** SKU 整包价快照（SKU_PACKAGE 计费口径使用） */
    private BigDecimal skuPackagePrice;
}
