package com.aid.billing.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 输入媒体计费说明 VO（模型级汇总，价格已乘折算倍率）。
 * 描述该模型「图片输入 / 视频输入」是否支持、输入单价与计费上限；
 * 单价为 null 时应继续查看档位级价格；两级均无正价时该类输入免费。
 *
 * @author 视觉AID
 */
@Data
public class InputPricingVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否支持图片输入（参考图） */
    private Boolean imageSupported;

    /** 输入图片模型级单价（Credits/张，已乘折算倍率）；null = 查看档位级价格 */
    private BigDecimal imageUnitPrice;

    /** 输入图片免费张数；null 等同 0 */
    private Integer imageFreeCount;

    /** 输入图片计费张数上限；null = 不限 */
    private Integer imageMaxCount;

    /** 是否支持视频输入（参考视频） */
    private Boolean videoSupported;

    /** 输入视频模型级单价（Credits/秒，已乘折算倍率）；null = 查看档位级价格 */
    private BigDecimal videoUnitPrice;

    /** 输入视频计费秒数上限；null = 不限 */
    private Integer videoMaxSeconds;

    /** 输入视频段数上限；null = 不限 */
    private Integer videoMaxCount;
}
