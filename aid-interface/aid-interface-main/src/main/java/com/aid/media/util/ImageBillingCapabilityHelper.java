package com.aid.media.util;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 图片计费能力辅助工具：对 {@code expectedImageCount} 做模型级上限保护，
 * 避免恶意前端传大数导致超额预扣。
 * 路由层确保所有 jimeng-image-* 全部进入 Jimeng provider、qwen-image-* / wan* 进入 Dashscope、
 * seedream* 进入方舟；此处按 modelCode 前缀给出业务保守上限。
 */
public final class ImageBillingCapabilityHelper {

    /** 最小张数：预扣必须至少 1 张。 */
    public static final int MIN_OUTPUT_COUNT = 1;

    /** 全局保底上限：任何模型超过此值都会被压回，防止预扣爆炸。 */
    public static final int GLOBAL_MAX_OUTPUT_COUNT = 15;

    private ImageBillingCapabilityHelper() {
    }

    /**
     * 返回模型按 modelCode 硬编码的出图张数上限（兜底值）。
     * 优先读取 aid_ai_model.max_output_count；本方法仅作为配置缺失/异常时的兜底。
     *
     * @param modelCode 模型 code，如 {@code jimeng-image-ultra}
     * @return 建议的单次请求最大出图张数，找不到规则时返回 {@link #GLOBAL_MAX_OUTPUT_COUNT}
     */
    public static int resolveMaxOutputCount(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return GLOBAL_MAX_OUTPUT_COUNT;
        }
        String code = modelCode.toLowerCase(Locale.ROOT);
        if (code.equals("jimeng-image-ultra")) {
            return 1;
        }
        if (code.equals("jimeng-image-3.1")) {
            // 官方按调用次数计费，单次固定出图 1 张
            return 1;
        }
        if (code.equals("jimeng-image-4.0")) {
            // 官方最大输出 = 15 - 输入图数，稳定组图建议 ≤9 张
            return 9;
        }
        if (code.equals("jimeng-image-4.6")) {
            // 官方最大输出 = 15 - 输入图数，建议输出 ≤6 张
            return 6;
        }
        if (code.startsWith("seedream") || code.contains("seedream")) {
            return 4;
        }
        if (code.startsWith("qwen-image") || code.startsWith("wan")) {
            return 4;
        }
        return GLOBAL_MAX_OUTPUT_COUNT;
    }

    /**
     * 返回模型最终生效的出图张数上限：。
     *
     * @param modelCode                模型 code（兜底分支需要）
     * @param configuredMaxOutputCount 后台运营配置的最大张数（可空）
     */
    public static int resolveEffectiveMaxOutputCount(String modelCode, Integer configuredMaxOutputCount) {
        if (configuredMaxOutputCount != null && configuredMaxOutputCount > 0) {
            return configuredMaxOutputCount;
        }
        int fallback = resolveMaxOutputCount(modelCode);
        return fallback > 0 ? fallback : GLOBAL_MAX_OUTPUT_COUNT;
    }

    /**
     * 对 expectedImageCount 做下限/上限压裁：空/小于 1 置 1，大于模型上限压回上限。
     * 该重载未传配置值，全部走硬编码兜底，保留旧调用兼容。
     *
     * @param modelCode       模型 code
     * @param expectedCount   前端/业务层提取出的目标张数
     * @return 受保护后的最终张数，保证处于 [1, maxOutputCount]
     */
    public static int normalizeExpectedCount(String modelCode, Integer expectedCount) {
        return normalizeExpectedCount(modelCode, expectedCount, null);
    }

    /**
     * 对 expectedImageCount 做下限/上限压裁；上限优先使用运营配置值。
     *
     * @param modelCode                模型 code
     * @param expectedCount            前端/业务层提取出的目标张数
     * @param configuredMaxOutputCount aid_ai_model.max_output_count（可空）
     * @return 受保护后的最终张数，保证处于 [1, effectiveMax]
     */
    public static int normalizeExpectedCount(String modelCode, Integer expectedCount, Integer configuredMaxOutputCount) {
        int max = resolveEffectiveMaxOutputCount(modelCode, configuredMaxOutputCount);
        int raw = expectedCount == null ? MIN_OUTPUT_COUNT : expectedCount;
        if (raw < MIN_OUTPUT_COUNT) {
            raw = MIN_OUTPUT_COUNT;
        }
        if (raw > max) {
            raw = max;
        }
        return raw;
    }
}
