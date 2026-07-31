package com.aid.billing.service;

import java.math.BigDecimal;

import com.aid.aid.domain.AidAiModel;
import com.aid.billing.dto.BillingDetailRequest;
import com.aid.billing.vo.BillingDetailGroupVO;
import com.aid.billing.vo.ModelBillingDetailVO;

/**
 * 计费详情查询 Service（C 端公共接口）。
 * 读取当前正在运行（状态正常 + 供应商正常）的模型，按大类分组返回各自的计费规则详情，
 * 价格按「官方原价（元）× 模型基础倍率（积分/元）× 单模型倍率」折算为最终积分价。
 *
 * @author 视觉AID
 */
public interface IBillingDetailQueryService
{
    /**
     * 查询计费详情（按 LLM / 图片 / 视频 / 配音 分组）。
     *
     * @param request 查询请求（modelType / modelName 均可选）
     * @return 分组后的计费详情
     */
    BillingDetailGroupVO queryBillingDetail(BillingDetailRequest request);

    /**
     * 读取模型基础倍率（积分/元）。
     * 模型池等批量组装计费明细时先取一次，循环内复用，避免每个模型重复查 aid_config。
     *
     * @return 模型基础倍率（未配置 / 异常时为 1）
     */
    BigDecimal readGlobalPriceFactor();

    /**
     * 组装单个模型的计费详情（口径与 {@link #queryBillingDetail} 完全一致）。
     * 供模型池 / 生成设置等返回模型的接口把计费明细（含各档位 SKU 价格与表头列定义）同步返回给前端。
     *
     * @param model        模型实体（需含 billingMode / billingRuleJson / billingMultiplier / costCredits / remark 字段）
     * @param providerName 供应商名称
     * @param providerLogo 供应商LOGO图标URL
     * @param globalFactor 全局折算系数（{@link #readGlobalPriceFactor()} 返回值；null / 非正数按 1 处理）
     * @return 计费详情 VO
     */
    ModelBillingDetailVO buildModelBillingDetail(AidAiModel model, String providerName, String providerLogo, BigDecimal globalFactor);

    /**
     * 折算单个模型的展示积分：官方原价（元）× 模型基础倍率 × 单模型倍率。
     * 所有返回给 C 端的模型价格必须经过此折算，不允许直出库表原价。
     *
     * @param model        模型实体（需含 costCredits / billingMultiplier 字段）
     * @param globalFactor 全局折算系数（{@link #readGlobalPriceFactor()} 返回值；null / 非正数按 1 处理）
     * @return 折算后的展示单价（costCredits 为空时返回 0）
     */
    BigDecimal displayCostCredits(AidAiModel model, BigDecimal globalFactor);
}
