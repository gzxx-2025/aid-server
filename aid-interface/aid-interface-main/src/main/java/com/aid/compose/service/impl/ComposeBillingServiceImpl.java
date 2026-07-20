package com.aid.compose.service.impl;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.aid.billing.service.IAccountUpdateService;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeBillingSnapshot;
import com.aid.compose.service.ComposeBillingService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 合成阶梯计费实现（独立合成分支）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComposeBillingServiceImpl implements ComposeBillingService {

    /** 计费业务类型 */
    private static final String BIZ_TYPE = "compose";

    /** 一分钟秒数 */
    private static final long SECONDS_PER_MINUTE = 60L;

    /** 默认分辨率档 */
    private static final String DEFAULT_RESOLUTION = "FHD";

    /** H.264 中国大陆默认档位原价表（元/分钟），仅当 aid_config 未配置 pricingTiers 时兜底 */
    private static final Map<String, BigDecimal> DEFAULT_TIERS = buildDefaultTiers();

    /** MPS 配置管理器 */
    private final MpsConfigManager mpsConfigManager;

    /** 统一账户变更执行器 */
    private final IAccountUpdateService accountUpdateService;

    @Override
    public ComposeBillingSnapshot freeze(Long userId, long estimatedSeconds, String resolution, String traceId) {
        ComposeBillingSnapshot snapshot = buildSnapshot(estimatedSeconds, resolution);
        BigDecimal frozen = snapshot.getFrozenCredits();
        if (isZero(frozen)) {
            log.info("合成预冻结积分为0,跳过冻结, userId={}, traceId={}, seconds={}, resolution={}",
                    userId, traceId, estimatedSeconds, snapshot.getResolution());
            return snapshot;
        }
        accountUpdateService.freeze(userId, frozen, traceId, BIZ_TYPE, "视频合成预冻结");
        log.info("合成预冻结成功, userId={}, traceId={}, frozen={}, seconds={}, resolution={}",
                userId, traceId, frozen, estimatedSeconds, snapshot.getResolution());
        return snapshot;
    }

    @Override
    public void settle(Long userId, long actualSeconds, ComposeBillingSnapshot snapshot, String traceId) {
        if (Objects.isNull(snapshot)) {
            log.error("合成结算缺少计费快照, userId={}, traceId={}", userId, traceId);
            throw new RuntimeException("结算异常");
        }
        BigDecimal frozen = nullToZero(snapshot.getFrozenCredits());
        // 实扣积分 = ceil(实际秒数/60) × 档单价
        BigDecimal actualCredits = computeCredits(actualSeconds, snapshot.getCreditPerMinute());
        // 结算金额从冻结里扣，封顶不超过冻结额
        BigDecimal settleAmount = actualCredits.min(frozen);
        if (isZero(settleAmount)) {
            log.info("合成结算实扣为0,跳过结算, userId={}, traceId={}, actualSeconds={}", userId, traceId, actualSeconds);
        } else {
            accountUpdateService.settle(userId, settleAmount, traceId, BIZ_TYPE, "视频合成结算");
            log.info("合成结算成功, userId={}, traceId={}, settle={}, actualSeconds={}",
                    userId, traceId, settleAmount, actualSeconds);
        }
        BigDecimal refundDiff = frozen.subtract(actualCredits);
        if (refundDiff.compareTo(BigDecimal.ZERO) > 0) {
            accountUpdateService.settleRefundFromFrozen(userId, refundDiff, traceId, BIZ_TYPE, "视频合成差额退款");
            log.info("合成结算差额退款, userId={}, traceId={}, refund={}", userId, traceId, refundDiff);
        }
        BigDecimal extra = actualCredits.subtract(frozen);
        if (extra.compareTo(BigDecimal.ZERO) > 0) {
            accountUpdateService.settleExtraCharge(userId, extra, traceId, BIZ_TYPE, "视频合成补扣");
            log.info("合成结算补扣, userId={}, traceId={}, extra={}", userId, traceId, extra);
        }
    }

    @Override
    public void refund(Long userId, ComposeBillingSnapshot snapshot, String traceId) {
        if (Objects.isNull(snapshot)) {
            log.error("合成退款缺少计费快照, userId={}, traceId={}", userId, traceId);
            throw new RuntimeException("退款异常");
        }
        BigDecimal frozen = nullToZero(snapshot.getFrozenCredits());
        // 0 金额规则：原冻结为 0 跳过 refund，绝不调用账户执行器、绝不写流水
        if (isZero(frozen)) {
            log.info("合成失败退款原冻结为0,跳过退款, userId={}, traceId={}", userId, traceId);
            return;
        }
        accountUpdateService.refund(userId, frozen, traceId, BIZ_TYPE, "视频合成失败退款");
        log.info("合成失败全额退款成功, userId={}, traceId={}, refund={}", userId, traceId, frozen);
    }

    /**
     * 构建计费快照：解析档单价并算出预冻结积分。
     *
     * @param estimatedSeconds 估算秒数
     * @param resolution       分辨率档
     * @return 计费快照
     */
    private ComposeBillingSnapshot buildSnapshot(long estimatedSeconds, String resolution) {
        MpsProperties props = mpsConfigManager.getMpsProperties();
        String tier = StrUtil.isBlank(resolution) ? DEFAULT_RESOLUTION : resolution.trim().toUpperCase();
        BigDecimal unitPrice = resolveUnitPrice(tier);
        int creditRate = props.getCreditRate();
        BigDecimal profit = Objects.isNull(props.getProfitMultiplier())
                ? BigDecimal.ONE : props.getProfitMultiplier();
        // 档单价(积分/分钟) = 原价 × 汇率 × 利润倍率
        BigDecimal creditPerMinute = unitPrice
                .multiply(BigDecimal.valueOf(creditRate))
                .multiply(profit);
        BigDecimal frozen = computeCredits(estimatedSeconds, creditPerMinute);

        ComposeBillingSnapshot snapshot = new ComposeBillingSnapshot();
        snapshot.setResolution(tier);
        snapshot.setCodec(props.getCodec());
        snapshot.setUnitPriceYuan(unitPrice);
        snapshot.setCreditRate(creditRate);
        snapshot.setProfitMultiplier(profit);
        snapshot.setCreditPerMinute(creditPerMinute);
        snapshot.setEstimatedSeconds(estimatedSeconds);
        snapshot.setFrozenCredits(frozen);
        return snapshot;
    }

    /**
     * 计算应扣积分：{@code ceil(seconds/60) × creditPerMinute}。
     * 计费结果关于秒数单调不减，同一分钟桶内取值相等（向上取整到分钟）。
     *
     * @param seconds         秒数（≥0）
     * @param creditPerMinute 档单价（积分/分钟）
     * @return 应扣积分
     */
    public BigDecimal computeCredits(long seconds, BigDecimal creditPerMinute) {
        if (seconds <= 0 || isZero(creditPerMinute)) {
            return BigDecimal.ZERO;
        }
        // 向上取整到分钟
        long minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE;
        return creditPerMinute.multiply(BigDecimal.valueOf(minutes));
    }

    /**
     * 解析分辨率档原价（元/分钟）：优先取 aid_config 配置，缺失回退默认档位表，再缺则按 FHD。
     *
     * @param tier 分辨率档（大写）
     * @return 原价（元/分钟）
     */
    public BigDecimal resolveUnitPrice(String tier) {
        Map<String, BigDecimal> configured = mpsConfigManager.getPricingTiers();
        if (Objects.nonNull(configured) && configured.containsKey(tier)) {
            return configured.get(tier);
        }
        if (DEFAULT_TIERS.containsKey(tier)) {
            return DEFAULT_TIERS.get(tier);
        }
        // 未知档位回退默认档（FHD），避免空指针
        return DEFAULT_TIERS.get(DEFAULT_RESOLUTION);
    }

    /**
     * 是否为 0（含 null）。
     *
     * @param value 金额
     * @return true=为 0 或 null
     */
    private boolean isZero(BigDecimal value) {
        return Objects.isNull(value) || value.compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * null 安全转 0。
     *
     * @param value 金额
     * @return 非 null 金额
     */
    private BigDecimal nullToZero(BigDecimal value) {
        return Objects.isNull(value) ? BigDecimal.ZERO : value;
    }

    /**
     * H.264 中国大陆默认档位原价表（元/分钟）。
     *
     * @return 默认档位表
     */
    private static Map<String, BigDecimal> buildDefaultTiers() {
        Map<String, BigDecimal> tiers = new LinkedHashMap<>();
        tiers.put("SD", new BigDecimal("0.016"));
        tiers.put("HD", new BigDecimal("0.0325"));
        tiers.put("FHD", new BigDecimal("0.063"));
        tiers.put("2K", new BigDecimal("0.136"));
        tiers.put("4K", new BigDecimal("0.278"));
        return tiers;
    }
}
