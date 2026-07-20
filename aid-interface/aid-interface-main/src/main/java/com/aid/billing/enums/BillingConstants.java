package com.aid.billing.enums;

import java.math.BigDecimal;

/**
 * 计费模块常量
 */
public final class BillingConstants {

    private BillingConstants() {
    }

    // 计费模式
    public static final String MODE_FIXED = "FIXED";
    public static final String MODE_SKU = "SKU";

    // SKU匹配策略
    public static final String MATCH_STRATEGY_FIRST_HIT = "FIRST_HIT";

    /** 模型基础倍率配置分类：原价（元）乘此值换算为积分。 */
    public static final String MODEL_PRICE_MULTIPLIER_CATEGORY = "media";

    /** 模型基础倍率配置名（aid_config）。 */
    public static final String MODEL_PRICE_MULTIPLIER_KEY = "ai_billing_global_multiplier";

    /** 模型基础倍率缓存时间。 */
    public static final long MODEL_PRICE_MULTIPLIER_CACHE_TTL_MS = 5000L;

    /** 开源默认口径：1 元官方原价换算为 100 积分。 */
    public static final BigDecimal DEFAULT_MODEL_PRICE_MULTIPLIER = new BigDecimal("100");

    // 文本模型默认字符转Token比例：约2个中文字符算1个Token（结算降级路径使用）
    public static final int DEFAULT_CHAR_TO_TOKEN_RATIO = 2;

    // 统一字符转Token换算：5个字 = 4个token → tokens = ceil(chars * 4 / 5)
    public static final int CHAR_TO_TOKEN_NUMERATOR = 4;
    public static final int CHAR_TO_TOKEN_DENOMINATOR = 5;

    /**
     * 统一字符转Token换算：tokens = ceil(chars * 4 / 5)
     * 仅用于预冻结阶段的 token 估算，结算阶段使用 provider 返回的真实 token。
     */
    public static int charsToTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) chars * CHAR_TO_TOKEN_NUMERATOR / CHAR_TO_TOKEN_DENOMINATOR);
    }

    // 文本模型默认预估输出字数
    public static final int DEFAULT_ESTIMATED_OUTPUT_CHARS = 2000;
    // 数据来源：doc/usedoc/LLM/谷歌LLM_Gemini3.1Pro.txt § "Aspect ratios and image size"
    //
    // 3.1 Flash Image Preview（14 种比例 × 4 档分辨率）：
    //   512→747, 1K→1120, 2K→1680, 4K→2520（各比例同值）
    // 3 Pro Image Preview（10 种比例 × 3 档分辨率，无 512）：
    //   1K→1120, 2K→1120, 4K→2000（各比例同值）

    /** Flash 支持的全部比例（14 种） */
    private static final java.util.Set<String> FLASH_RATIOS = java.util.Set.of(
            "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4", "8:1", "9:16", "16:9", "21:9");
    /** Pro 支持的全部比例（10 种） */
    private static final java.util.Set<String> PRO_RATIOS = java.util.Set.of(
            "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9");

    /**
     * 根据 modelCode + 分辨率 + 比例 查表返回预估输出 token。
     *
     * @param modelCode   模型编码，含 "flash" 走 Flash 表，含 "pro" 走 Pro 表
     * @param resolution  分辨率档位："512"/"1K"/"2K"/"4K"，null 按默认 2K
     * @param aspectRatio 宽高比："1:1"/"16:9"/"9:16" 等，null 按 1:1 兜底
     * @return 单张输出 token 预估值
     */
    public static int geminiImageOutputTokens(String modelCode, String resolution, String aspectRatio) {
        boolean isPro = modelCode != null && modelCode.toLowerCase().contains("pro");
        // 分辨率兜底 2K
        String res = (resolution == null || resolution.isBlank()) ? "2K" : resolution.trim().toUpperCase();
        // 比例兜底 1:1（Gemini 默认输出 1:1 正方图）
        String ratio = (aspectRatio == null || aspectRatio.isBlank()) ? "1:1" : aspectRatio.trim();

        // 验证比例是否为该模型支持的比例，不支持时兜底 1:1 并在调用方打日志
        java.util.Set<String> supported = isPro ? PRO_RATIOS : FLASH_RATIOS;
        if (!supported.contains(ratio)) {
            ratio = "1:1";
        }

        if (isPro) {
            // Pro 无 512 档，512 降为 1K
            return proTokens(res, ratio);
        } else {
            return flashTokens(res, ratio);
        }
    }

    /**
     * Flash token 查表：(resolution, aspectRatio) → tokens。
     * 当前各比例同值；如官方更新出现差异，在此按 ratio 分支即可。
     */
    private static int flashTokens(String res, String ratio) {
        return switch (res) {
            case "512" -> 747;    // 所有比例 = 747
            case "1K"  -> 1120;   // 所有比例 = 1120
            case "2K"  -> 1680;   // 所有比例 = 1680
            case "4K"  -> 2520;   // 所有比例 = 2520
            default    -> 1680;   // 未知档位按 2K 兜底
        };
    }

    /**
     * Pro token 查表：(resolution, aspectRatio) → tokens。
     * 当前各比例同值；如官方更新出现差异，在此按 ratio 分支即可。
     */
    private static int proTokens(String res, String ratio) {
        return switch (res) {
            case "1K"  -> 1120;   // 所有比例 = 1120
            case "2K"  -> 1120;   // 所有比例 = 1120
            case "4K"  -> 2000;   // 所有比例 = 2000
            default    -> 1120;   // 无 512 档；未知档位按 2K=1120 兜底
        };
    }
    // 数据来源：doc/usedoc/image/openai_imagee2 § "Cost and latency" / "Calculating costs"。
    // 官方给出 gpt-image-2 各 quality × size 的图像成本（USD），输出 token 单价 $30/1M（=$0.00003/token）。
    // 预估输出 token = 官方成本 ÷ $0.00003，向上取整并适度留余，保证预扣 ≥ 实际
    // （SKU settleMode=REFUND_ONLY 只退不补，宁高勿低，避免触发 TOKEN 补扣）。
    //   Low    : 方图 $0.006→200, 竖图 $0.005→170, 横图 $0.005→170
    //   Medium : 方图 $0.053→1800, 竖图 $0.041→1400, 横图 $0.041→1400
    //   High   : 方图 $0.211→7100, 竖图 $0.165→5500, 横图 $0.165→5500
    //   auto / 缺省 → 按 High 取最高；朝向未知 → 按方图（同档最高）取最高

    /** gpt-image-2 编辑模式下，单张参考图高保真输入 token 预估（官方称恒以高保真处理输入图，编辑请求 input token 显著增加） */
    public static final int GPT_IMAGE2_INPUT_TOKENS_PER_REF_IMAGE = 1800;

    /**
     * 根据 quality + 画幅朝向查表返回 gpt-image-2 单张预估输出 token。
     *
     * @param quality     质量档位："low"/"medium"/"high"/"auto"，空或 auto 按 high
     * @param orientation 画幅朝向："square"/"portrait"/"landscape"，空或未知按 square（同档最高）
     * @return 单张预估输出 token
     */
    public static int gptImage2OutputTokens(String quality, String orientation) {
        String q = (quality == null || quality.isBlank()) ? "high" : quality.trim().toLowerCase();
        // auto 视为 high：预扣取最高，只退不补
        if ("auto".equals(q)) {
            q = "high";
        }
        String o = (orientation == null || orientation.isBlank()) ? "square" : orientation.trim().toLowerCase();
        boolean square = "square".equals(o);
        return switch (q) {
            case "low"    -> square ? 200 : 170;
            case "medium" -> square ? 1800 : 1400;
            case "high"   -> square ? 7100 : 5500;
            // 未知质量按 high 兜底，确保预扣不偏低
            default       -> square ? 7100 : 5500;
        };
    }

    // 结算策略（与SettleMode枚举对应）
    public static final String SETTLE_DIRECT = "DIRECT_SETTLE";
    public static final String SETTLE_REFUND_ONLY = "REFUND_ONLY";

    // Usage来源
    public static final String USAGE_PROVIDER = "PROVIDER_USAGE";
    public static final String USAGE_ESTIMATE = "ESTIMATE";
}
