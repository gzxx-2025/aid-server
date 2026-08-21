package com.aid.billing.model;

import lombok.Data;

import java.util.Map;

/**
 * 视频 token 预冻结估算规则。仅在 billing_rule_json 显式配置时启用，
 * 避免用厂商/模型编码硬编码计费公式。
 */
@Data
public class VideoTokenEstimateRule {

    /** 当前支持 PIXEL_FPS：像素数 × 帧率 × 计费秒数 / tokenDivisor。 */
    private String strategy;

    private Integer framesPerSecond;

    private Integer tokenDivisor;

    /** duration=-1 或未提供时，按该最大输出秒数安全预冻结。 */
    private Integer autoDurationMaxSeconds;

    /** 存在输入视频时，不采信客户端时长，按该总秒数上限安全预冻结。 */
    private Integer inputVideoMaxSeconds;

    /** 分辨率缺失或未知时采用的安全兜底档位（通常配置为成本最高档）。 */
    private String fallbackResolution;

    /** 官方最低计费输入秒数 = ceil(outputSeconds * numerator / denominator)。 */
    private Integer minimumInputSecondsNumerator;

    private Integer minimumInputSecondsDenominator;

    /** resolution -> ratio -> [width, height]；可配置 "default" 比例兜底。 */
    private Map<String, Map<String, int[]>> dimensions;
}
