package com.aid.billing.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单条计费规则明细 VO（对应一个 SKU / 一个计费档位 = 表格的一行）。
 *
 * @author 视觉AID
 */
@Data
public class BillingRuleItemVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** SKU 编码（FIXED 固定价时为 null） */
    private String skuCode;

    /** 档位名称（如「首尾帧720P 1-5秒」「输入Token 0-32K」） */
    private String skuName;
    /** 分辨率（如 720P / 1080P / 2K / 4K），无则为 null */
    private String resolution;

    /** 生成模式（如 TEXT_TO_IMAGE / KF2V），无则为 null */
    private String generateMode;

    /** 时长区间下限（秒），仅视频按秒 / 套餐计费时有效 */
    private Integer durationMin;

    /** 时长区间上限（秒），仅视频按秒 / 套餐计费时有效 */
    private Integer durationMax;

    /** 输入 Token 区间下限，仅文本按 Token 计费时有效 */
    private Integer inputTokensMin;

    /** 输入 Token 区间上限，仅文本按 Token 计费时有效 */
    private Integer inputTokensMax;

    /** 参考图片数量下限，null 表示不限制 */
    private Integer referenceImageCountMin;

    /** 参考图片数量上限，null 表示不限制 */
    private Integer referenceImageCountMax;

    /** 输入视频数量下限，null 表示不限制 */
    private Integer inputVideoCountMin;

    /** 输入视频数量上限，null 表示不限制 */
    private Integer inputVideoCountMax;

    /** 音频模式条件（如 off / native），无则为 null */
    private String audioMode;
    /** 单价：按张计费=每张价；固定计费=每次价；其它口径为 null */
    private BigDecimal unitPrice;

    /** 每秒单价：按秒计费时有效，其它为 null */
    private BigDecimal pricePerSecond;

    /** 整包价：按套餐计费时有效，其它为 null */
    private BigDecimal packagePrice;

    /** 输入单价（Credits/百万Token）：文本按 Token 计费时有效 */
    private BigDecimal inputPricePerMillion;

    /** 输出单价（Credits/百万Token）：文本按 Token 计费时有效 */
    private BigDecimal outputPricePerMillion;

    /** 输入图片单价（Credits/张，本档位覆盖值，已乘折算倍率）；null = 回退模型级 inputPricing */
    private BigDecimal inputImagePrice;

    /** 输入图片免费张数（本档位覆盖值）；null = 用模型级 inputPricing */
    private Integer inputImageFreeCount;

    /** 输入视频单价（Credits/秒，本档位覆盖值，已乘折算倍率）；null = 回退模型级 inputPricing */
    private BigDecimal inputVideoPricePerSecond;

}
