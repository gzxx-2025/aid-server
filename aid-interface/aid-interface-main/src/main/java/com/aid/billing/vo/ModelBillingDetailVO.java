package com.aid.billing.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 单个模型的计费详情 VO。
 * 汇总一个 AI 模型的计费口径、各档位明细与整体说明，供前端「计费详情」页面渲染。
 *
 * @author 视觉AID
 */
@Data
public class ModelBillingDetailVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private Long id;

    /** 模型展示/选择码 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 所属服务商名称 */
    private String providerName;

    /** 所属服务商LOGO图标URL（厂家品牌图标） */
    @com.aid.common.aid.oss.annotation.MediaUrl
    private String providerLogo;

    /** 模型大类：text / image / video / audio */
    private String modelType;

    /** 模型大类中文名：文本生成模型 / 图片生成模型 / 视频生成模型 / 配音模型 */
    private String modelTypeName;

    /** 生成模式细分（text / text_to_image / image_to_video / audio 等） */
    private String generateMode;

    /** 计费模式：FIXED-固定价、SKU-按规则 */
    private String billingMode;

    /** 计费口径：TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE / FIXED */
    private String meterType;

    /** 计费口径中文名：按Token阶梯计费 / 按张计费 / 按秒计费 / 按套餐计费 / 固定单价 */
    private String meterTypeName;

    /**
     * 价格折算倍率（模型基础倍率 × 单模型倍率）。
     * 明细中的价格已乘以本倍率，此处单独暴露用于前端展示「当前加价/折扣」。
     */
    private BigDecimal priceMultiplier;

    /** 计费整体说明（按计费口径自动生成的可读文案，用于表格上方一句话说明） */
    private String billingDesc;

    /** 计费单位名称（Credits），与价格列配合展示 */
    private String creditUnit;

    /**
     * 计费档位表格的列定义（表头）。
     * 前端按本列表顺序渲染表头，并用每列 {@code key} 去 {@link BillingRuleItemVO} 取值填充。
     * 不同计费口径列不同（按Token / 按张 / 按秒 / 按套餐 / 固定单价）。
     */
    private List<BillingColumnVO> columns;

    /** 计费档位明细列表（表格的数据行） */
    private List<BillingRuleItemVO> rules;

    /**
     * 输入媒体计费说明（图片/视频输入附加费，价格已乘折算倍率）。
     * null = 模型未配置输入计费（输入免费）；单价 0 亦为免费。
     * SKU 级差异化输入价见 rules[].inputImagePrice / inputVideoPricePerSecond。
     */
    private InputPricingVO inputPricing;

    /** 模型备注（运营在模型上填写的说明） */
    private String remark;
}
