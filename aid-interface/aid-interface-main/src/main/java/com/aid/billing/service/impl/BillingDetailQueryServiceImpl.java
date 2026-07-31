package com.aid.billing.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.billing.dto.BillingDetailRequest;
import com.aid.billing.model.BillingRule;
import com.aid.billing.model.BillingSku;
import com.aid.billing.model.InputMediaPricing;
import com.aid.billing.service.IBillingDetailQueryService;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.vo.BillingColumnVO;
import com.aid.billing.vo.BillingDetailGroupVO;
import com.aid.billing.vo.BillingRuleItemVO;
import com.aid.billing.vo.InputPricingVO;
import com.aid.billing.vo.ModelBillingDetailVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 计费详情查询 Service 实现（C 端公共接口）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDetailQueryServiceImpl implements IBillingDetailQueryService
{
    /** 状态正常 */
    private static final String STATUS_NORMAL = "0";
    /** 删除标志正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 计费单位名称（用户侧展示口径） */
    private static final String CREDIT_UNIT = "Credits";
    /** Token 单价单位 */
    private static final String UNIT_PER_MILLION_TOKEN = "Credits/百万Token";
    /** 秒单位 */
    private static final String UNIT_SECOND = "秒";

    /** 模型大类常量（与 AiModelBusinessServiceImpl 保持一致） */
    private static final String MODEL_TYPE_TEXT = "text";
    private static final String MODEL_TYPE_IMAGE = "image";
    private static final String MODEL_TYPE_VIDEO = "video";
    private static final String MODEL_TYPE_AUDIO = "audio";

    /** 计费口径常量 */
    private static final String METER_TOKEN = "TOKEN";
    private static final String METER_PER_IMAGE = "PER_IMAGE";
    private static final String METER_PER_SECOND = "PER_SECOND";
    private static final String METER_SKU_PACKAGE = "SKU_PACKAGE";
    private static final String METER_PER_CHAR = "PER_CHAR";
    private static final String METER_FIXED = "FIXED";

    /** 计费模式常量 */
    private static final String BILLING_MODE_SKU = "SKU";

    private final IAidAiModelService aiModelService;
    private final IAidAiProviderService aiProviderService;
    private final BillingPriceMultiplierService billingPriceMultiplierService;

    @Override
    public BillingDetailGroupVO queryBillingDetail(BillingDetailRequest request)
    {
        String modelType = Objects.isNull(request) ? null : request.getModelType();
        String modelName = Objects.isNull(request) ? null : request.getModelName();

        BillingDetailGroupVO group = new BillingDetailGroupVO();
        group.setCreditUnit(CREDIT_UNIT);

        // 注意：查询字段精简，仅取展示所需字段，新增字段时务必同步追加到 select
        LambdaQueryWrapper<AidAiProvider> providerWrapper = Wrappers.lambdaQuery();
        providerWrapper.select(AidAiProvider::getId, AidAiProvider::getProviderName, AidAiProvider::getLogoUrl);
        providerWrapper.eq(AidAiProvider::getStatus, STATUS_NORMAL);
        providerWrapper.eq(AidAiProvider::getDelFlag, DEL_FLAG_NORMAL);
        List<AidAiProvider> providers = aiProviderService.list(providerWrapper);
        Set<Long> availableProviderIds = providers.stream()
                .map(AidAiProvider::getId)
                .collect(Collectors.toSet());
        Map<Long, String> providerNameMap = providers.stream()
                .collect(Collectors.toMap(AidAiProvider::getId, AidAiProvider::getProviderName));
        // 供应商ID→LOGO映射（logo 可能为空，过滤后再收集，避免 toMap 空值 NPE）
        Map<Long, String> providerLogoMap = providers.stream()
                .filter(p -> Objects.nonNull(p.getLogoUrl()))
                .collect(Collectors.toMap(AidAiProvider::getId, AidAiProvider::getLogoUrl));
        if (CollectionUtil.isEmpty(availableProviderIds))
        {
            log.info("计费详情查询：无可用供应商");
            return group;
        }

        BigDecimal globalFactor = readGlobalPriceFactor();

        // 注意：计费详情展示需读取计费相关字段，新增计费字段时务必同步追加到 select
        LambdaQueryWrapper<AidAiModel> modelWrapper = Wrappers.lambdaQuery();
        modelWrapper.select(AidAiModel::getId, AidAiModel::getModelCode, AidAiModel::getModelName,
                AidAiModel::getModelType, AidAiModel::getGenerateMode, AidAiModel::getProviderId,
                AidAiModel::getCostCredits, AidAiModel::getBillingMultiplier,
                AidAiModel::getBillingMode, AidAiModel::getBillingRuleJson,
                // 输入媒体计费说明所需：图片输入支持标记 + capability（视频输入支持判定）
                AidAiModel::getSupportsImageInput, AidAiModel::getCapabilityJson,
                AidAiModel::getPriority, AidAiModel::getRemark);
        modelWrapper.eq(AidAiModel::getStatus, STATUS_NORMAL);
        modelWrapper.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        modelWrapper.in(AidAiModel::getProviderId, availableProviderIds);
        if (StrUtil.isNotBlank(modelType))
        {
            modelWrapper.eq(AidAiModel::getModelType, modelType);
        }
        // 按名称模糊搜索（可选）：同时匹配展示名与展示码，便于用户搜索
        if (StrUtil.isNotBlank(modelName))
        {
            modelWrapper.and(w -> w.like(AidAiModel::getModelName, modelName)
                    .or().like(AidAiModel::getModelCode, modelName));
        }
        modelWrapper.orderByDesc(AidAiModel::getPriority);
        List<AidAiModel> models = aiModelService.list(modelWrapper);
        if (CollectionUtil.isEmpty(models))
        {
            log.info("计费详情查询：无匹配模型, modelType={}, modelName={}", modelType, modelName);
            return group;
        }

        for (AidAiModel model : models)
        {
            ModelBillingDetailVO vo = buildModelDetail(model, providerNameMap.get(model.getProviderId()),
                    providerLogoMap.get(model.getProviderId()), globalFactor);
            String type = model.getModelType();
            if (Objects.equals(MODEL_TYPE_TEXT, type))
            {
                group.getLlm().add(vo);
            }
            else if (Objects.equals(MODEL_TYPE_IMAGE, type))
            {
                group.getImage().add(vo);
            }
            else if (Objects.equals(MODEL_TYPE_VIDEO, type))
            {
                group.getVideo().add(vo);
            }
            else if (Objects.equals(MODEL_TYPE_AUDIO, type))
            {
                group.getVoice().add(vo);
            }
            else
            {
                // 未知大类（理论上不会出现）：仅记录日志，避免数据落入错误分组
                log.info("计费详情查询：未知模型大类, modelId={}, modelType={}", model.getId(), type);
            }
        }

        log.info("计费详情查询完成: modelType={}, modelName={}, LLM={}, 图片={}, 视频={}, 配音={}",
                modelType, modelName, group.getLlm().size(), group.getImage().size(),
                group.getVideo().size(), group.getVoice().size());
        return group;
    }

    @Override
    public BigDecimal readGlobalPriceFactor()
    {
        return billingPriceMultiplierService.getGlobalMultiplier();
    }

    @Override
    public ModelBillingDetailVO buildModelBillingDetail(AidAiModel model, String providerName, String providerLogo, BigDecimal globalFactor)
    {
        // 系数空值/非正数兜底为 1，保证外部调用不会算出 0 价
        BigDecimal factor = resolvePositive(globalFactor);
        return buildModelDetail(model, providerName, providerLogo, factor);
    }

    /**
     * 折算单个模型的展示积分：官方原价（元）× 模型基础倍率 × 单模型倍率。
     *
     * @param model        模型实体（需含 costCredits / billingMultiplier 字段）
     * @param globalFactor 全局折算系数（null / 非正数按 1 处理）
     * @return 折算后的展示单价（costCredits 为空时返回 0）
     */
    @Override
    public BigDecimal displayCostCredits(AidAiModel model, BigDecimal globalFactor)
    {
        // 折算倍率 = 模型基础倍率 × 单模型倍率（口径与 buildModelDetail 完全一致）
        BigDecimal modelMultiplier = resolvePositive(model.getBillingMultiplier());
        BigDecimal priceMultiplier = modelMultiplier.multiply(resolvePositive(globalFactor));
        return display(model.getCostCredits(), priceMultiplier);
    }

    /**
     * 组装单个模型的计费详情。
     *
     * @param model        模型实体
     * @param providerName 供应商名称
     * @param providerLogo 供应商LOGO图标URL
     * @param globalFactor 模型基础倍率（积分/元）
     * @return 计费详情 VO
     */
    private ModelBillingDetailVO buildModelDetail(AidAiModel model, String providerName, String providerLogo, BigDecimal globalFactor)
    {
        ModelBillingDetailVO vo = new ModelBillingDetailVO();
        vo.setId(model.getId());
        vo.setModelCode(model.getModelCode());
        vo.setModelName(model.getModelName());
        vo.setProviderName(providerName);
        vo.setProviderLogo(providerLogo);
        vo.setModelType(model.getModelType());
        vo.setModelTypeName(resolveModelTypeName(model.getModelType()));
        vo.setGenerateMode(model.getGenerateMode());
        vo.setBillingMode(model.getBillingMode());
        vo.setCreditUnit(CREDIT_UNIT);
        vo.setRemark(model.getRemark());

        // 折算倍率 = 单模型倍率 × 模型基础倍率
        BigDecimal modelMultiplier = resolvePositive(model.getBillingMultiplier());
        BigDecimal priceMultiplier = modelMultiplier.multiply(globalFactor);
        vo.setPriceMultiplier(scale(priceMultiplier));

        // SKU 模式且有规则 JSON：按 meterType 渲染多档位；否则按 FIXED 单价渲染
        boolean isSku = Objects.equals(BILLING_MODE_SKU, model.getBillingMode())
                && StrUtil.isNotBlank(model.getBillingRuleJson());
        if (isSku)
        {
            BillingRule rule = parseRuleSafely(model);
            if (Objects.nonNull(rule))
            {
                String meterType = StrUtil.isNotBlank(rule.getMeterType())
                        ? rule.getMeterType().toUpperCase()
                        : inferMeterType(model.getModelType());
                vo.setMeterType(meterType);
                vo.setMeterTypeName(resolveMeterTypeName(meterType));
                vo.setBillingDesc(resolveBillingDesc(meterType));
                vo.setColumns(buildColumns(meterType, rule));
                vo.setRules(buildSkuRules(rule, meterType, priceMultiplier));
                // 输入媒体计费说明（图片/视频输入是否支持、单价、上限）
                vo.setInputPricing(buildInputPricingVo(model, rule, priceMultiplier));
                return vo;
            }
            // 规则解析失败：降级为 FIXED 展示，避免前端拿到空详情
            log.info("计费详情：SKU规则解析失败降级为固定价展示, modelId={}", model.getId());
        }

        // FIXED：固定单价（无 SKU 规则时输入计费同样按未配置=免费展示）
        buildFixedRule(vo, model, priceMultiplier);
        vo.setInputPricing(buildInputPricingVo(model, null, priceMultiplier));
        return vo;
    }

    /**
     * 组装输入媒体计费说明：支持标记来自模型列/capability，单价来自规则级 inputPricing（乘折算倍率）。
     * 文本模型且无输入计费配置时返回 null（避免无意义字段）；图片/视频模型始终返回（体现是否支持与免费语义）。
     */
    private InputPricingVO buildInputPricingVo(AidAiModel model, BillingRule rule, BigDecimal priceMultiplier)
    {
        InputMediaPricing pricing = Objects.isNull(rule) ? null : rule.getInputPricing();
        boolean mediaModel = Objects.equals(MODEL_TYPE_IMAGE, model.getModelType())
                || Objects.equals(MODEL_TYPE_VIDEO, model.getModelType());
        if (Objects.isNull(pricing) && !mediaModel)
        {
            return null;
        }
        InputPricingVO vo = new InputPricingVO();
        // 图片输入支持：模型列 supports_image_input
        vo.setImageSupported(Boolean.TRUE.equals(model.getSupportsImageInput()));
        // 视频输入支持：capability_json 标记，或计费规则显式配置了视频输入价
        vo.setVideoSupported(readVideoInputSupported(model.getCapabilityJson())
                || (Objects.nonNull(pricing) && Objects.nonNull(pricing.getVideo())));
        if (Objects.nonNull(pricing))
        {
            if (Objects.nonNull(pricing.getImage()))
            {
                vo.setImageUnitPrice(display(pricing.getImage().getUnitPrice(), priceMultiplier));
                vo.setImageMaxCount(pricing.getImage().getMaxCount());
            }
            if (Objects.nonNull(pricing.getVideo()))
            {
                vo.setVideoUnitPrice(display(pricing.getVideo().getUnitPrice(), priceMultiplier));
                vo.setVideoMaxSeconds(pricing.getVideo().getMaxSeconds());
                vo.setVideoMaxCount(pricing.getVideo().getMaxCount());
            }
        }
        return vo;
    }

    /**
     * 从 capability_json 读取视频输入支持标记（supportsVideoInput=true 或 maxReferenceVideos>0）。
     */
    private boolean readVideoInputSupported(String capabilityJson)
    {
        if (StrUtil.isBlank(capabilityJson))
        {
            return false;
        }
        try
        {
            JSONObject obj = JSONUtil.parseObj(capabilityJson);
            if (Boolean.TRUE.equals(obj.getBool("supportsVideoInput")))
            {
                return true;
            }
            Integer maxVideos = obj.getInt("maxReferenceVideos");
            return Objects.nonNull(maxVideos) && maxVideos > 0;
        }
        catch (Exception e)
        {
            log.info("计费详情：解析 capability_json 视频输入标记失败，按不支持处理, err={}", e.getMessage());
            return false;
        }
    }

    /**
     * 渲染 FIXED 固定价：单条规则 + 固定价表头。
     */
    private void buildFixedRule(ModelBillingDetailVO vo, AidAiModel model, BigDecimal priceMultiplier)
    {
        String type = model.getModelType();
        vo.setMeterType(METER_FIXED);
        vo.setMeterTypeName("固定单价");
        // 图片固定价语义上是「每张」，其余为「每次」
        boolean perImage = Objects.equals(MODEL_TYPE_IMAGE, type);
        vo.setBillingDesc(perImage ? "固定单价，每张按下方价格扣费" : "固定单价，每次生成按下方价格扣费");
        vo.setColumns(buildFixedColumns(perImage));

        BigDecimal unit = display(model.getCostCredits(), priceMultiplier);
        BillingRuleItemVO item = new BillingRuleItemVO();
        item.setSkuName("固定单价");
        item.setUnitPrice(unit);
        List<BillingRuleItemVO> rules = new ArrayList<>();
        rules.add(item);
        vo.setRules(rules);
    }

    /**
     * 渲染 SKU 多档位规则：按 priority 升序、仅取启用档位，命中条件与价格结构化拆分。
     */
    private List<BillingRuleItemVO> buildSkuRules(BillingRule rule, String meterType, BigDecimal priceMultiplier)
    {
        List<BillingRuleItemVO> rules = new ArrayList<>();
        if (CollectionUtil.isEmpty(rule.getSkus()))
        {
            return rules;
        }
        // 按 priority 升序展示，口径与命中顺序一致
        List<BillingSku> skus = rule.getSkus().stream()
                .filter(Objects::nonNull)
                .filter(BillingSku::isEnabled)
                .sorted(Comparator.comparingInt(BillingSku::getPriority))
                .collect(Collectors.toList());
        for (BillingSku sku : skus)
        {
            BillingRuleItemVO item = new BillingRuleItemVO();
            item.setSkuCode(sku.getSkuCode());
            item.setSkuName(sku.getSkuName());
            item.setRemark(sku.getRemark());
            // 命中条件结构化拆分（按需出现）
            fillCondition(item, sku.getMatch());
            fillSkuPrice(item, sku, meterType, priceMultiplier);
            // SKU 级输入媒体价覆盖（如视频输入单价随分辨率变化）
            fillSkuInputPricing(item, sku, priceMultiplier);
            rules.add(item);
        }
        return rules;
    }

    /**
     * 将 SKU 命中条件 Map 拆分为结构化字段（分辨率 / 时长区间 / Token 区间 / 生成模式）。
     */
    private void fillCondition(BillingRuleItemVO item, Map<String, Object> match)
    {
        if (CollectionUtil.isEmpty(match))
        {
            return;
        }
        Object resolution = match.get("resolution");
        if (Objects.nonNull(resolution))
        {
            item.setResolution(String.valueOf(resolution));
        }
        Object genMode = match.get("generateMode");
        if (Objects.nonNull(genMode))
        {
            item.setGenerateMode(String.valueOf(genMode));
        }
        item.setDurationMin(matchIntOrNull(match, "durationMin"));
        item.setDurationMax(matchIntOrNull(match, "durationMax"));
        item.setInputTokensMin(matchIntOrNull(match, "inputTokensMin"));
        item.setInputTokensMax(matchIntOrNull(match, "inputTokensMax"));
    }

    /**
     * 按计费口径填充 SKU 的价格字段（结构化，不拼接文字）。
     */
    private void fillSkuPrice(BillingRuleItemVO item, BillingSku sku, String meterType, BigDecimal priceMultiplier)
    {
        switch (meterType)
        {
            case METER_TOKEN ->
            {
                item.setInputPricePerMillion(display(sku.getInputPricePerMillion(), priceMultiplier));
                item.setOutputPricePerMillion(display(sku.getOutputPricePerMillion(), priceMultiplier));
            }
            case METER_PER_IMAGE -> item.setUnitPrice(display(sku.getPrice(), priceMultiplier));
            case METER_PER_SECOND ->
            {
                // 优先每秒单价；缺失时用整包价 / durationMax 反推（与计费器口径一致）
                item.setPricePerSecond(display(resolvePerSecondRaw(sku), priceMultiplier));
            }
            case METER_SKU_PACKAGE -> item.setPackagePrice(display(sku.getPrice(), priceMultiplier));
            case METER_PER_CHAR -> item.setUnitPrice(display(sku.getPricePerChar(), priceMultiplier));
            default -> item.setUnitPrice(display(sku.getPrice(), priceMultiplier));
        }
    }

    /**
     * 填充 SKU 级输入媒体单价（覆盖模型级 inputPricing 的档位差异化价，已乘折算倍率）。
     */
    private void fillSkuInputPricing(BillingRuleItemVO item, BillingSku sku, BigDecimal priceMultiplier)
    {
        InputMediaPricing pricing = sku.getInputPricing();
        if (Objects.isNull(pricing))
        {
            return;
        }
        if (Objects.nonNull(pricing.getImage()))
        {
            item.setInputImagePrice(display(pricing.getImage().getUnitPrice(), priceMultiplier));
        }
        if (Objects.nonNull(pricing.getVideo()))
        {
            item.setInputVideoPricePerSecond(display(pricing.getVideo().getUnitPrice(), priceMultiplier));
        }
    }

    /**
     * 解析每秒原始单价：优先 sku.pricePerSecond，缺失时用 price / durationMax 反推。
     */
    private BigDecimal resolvePerSecondRaw(BillingSku sku)
    {
        if (Objects.nonNull(sku.getPricePerSecond()) && sku.getPricePerSecond().compareTo(BigDecimal.ZERO) > 0)
        {
            return sku.getPricePerSecond();
        }
        BigDecimal total = Objects.isNull(sku.getPrice()) ? BigDecimal.ZERO : sku.getPrice();
        int maxDur = Objects.isNull(matchIntOrNull(sku.getMatch(), "durationMax")) ? 0
                : matchIntOrNull(sku.getMatch(), "durationMax");
        if (maxDur > 0 && total.compareTo(BigDecimal.ZERO) > 0)
        {
            return total.divide(BigDecimal.valueOf(maxDur), 6, RoundingMode.HALF_UP);
        }
        return total;
    }
    /** 按计费口径构建表格列定义（规则含输入媒体计费时追加输入价列） */
    private List<BillingColumnVO> buildColumns(String meterType, BillingRule rule)
    {
        List<BillingColumnVO> cols = new ArrayList<>();
        cols.add(new BillingColumnVO("skuName", "档位", null, "text"));
        switch (meterType)
        {
            case METER_TOKEN ->
            {
                cols.add(new BillingColumnVO("inputTokensMin", "输入Token下限", null, "number"));
                cols.add(new BillingColumnVO("inputTokensMax", "输入Token上限", null, "number"));
                cols.add(new BillingColumnVO("inputPricePerMillion", "输入单价", UNIT_PER_MILLION_TOKEN, "number"));
                cols.add(new BillingColumnVO("outputPricePerMillion", "输出单价", UNIT_PER_MILLION_TOKEN, "number"));
            }
            case METER_PER_IMAGE ->
            {
                cols.add(new BillingColumnVO("resolution", "分辨率", null, "text"));
                cols.add(new BillingColumnVO("generateMode", "生成模式", null, "text"));
                cols.add(new BillingColumnVO("unitPrice", "每张单价", CREDIT_UNIT, "number"));
            }
            case METER_PER_SECOND ->
            {
                cols.add(new BillingColumnVO("resolution", "分辨率", null, "text"));
                cols.add(new BillingColumnVO("durationMin", "时长下限", UNIT_SECOND, "number"));
                cols.add(new BillingColumnVO("durationMax", "时长上限", UNIT_SECOND, "number"));
                cols.add(new BillingColumnVO("pricePerSecond", "每秒单价", CREDIT_UNIT, "number"));
            }
            case METER_SKU_PACKAGE ->
            {
                cols.add(new BillingColumnVO("resolution", "分辨率", null, "text"));
                cols.add(new BillingColumnVO("durationMin", "时长下限", UNIT_SECOND, "number"));
                cols.add(new BillingColumnVO("durationMax", "时长上限", UNIT_SECOND, "number"));
                cols.add(new BillingColumnVO("packagePrice", "整包价", CREDIT_UNIT, "number"));
            }
            case METER_PER_CHAR -> cols.add(new BillingColumnVO("unitPrice", "每字符单价", CREDIT_UNIT, "number"));
            default -> cols.add(new BillingColumnVO("unitPrice", "单价", CREDIT_UNIT, "number"));
        }
        // 输入媒体计费列（仅规则或任一 SKU 配置了对应输入价时出现，避免无关模型多空列）
        if (hasInputPricing(rule, true))
        {
            cols.add(new BillingColumnVO("inputImagePrice", "输入图片单价", CREDIT_UNIT + "/张", "number"));
        }
        if (hasInputPricing(rule, false))
        {
            cols.add(new BillingColumnVO("inputVideoPricePerSecond", "输入视频单价", CREDIT_UNIT + "/秒", "number"));
        }
        cols.add(new BillingColumnVO("remark", "备注", null, "text"));
        return cols;
    }

    /**
     * 判断规则级或任一 SKU 级是否配置了输入媒体计费（image=true 查图片，false 查视频）。
     */
    private boolean hasInputPricing(BillingRule rule, boolean image)
    {
        if (Objects.isNull(rule))
        {
            return false;
        }
        if (Objects.nonNull(rule.getInputPricing())
                && Objects.nonNull(image ? rule.getInputPricing().getImage() : rule.getInputPricing().getVideo()))
        {
            return true;
        }
        if (CollectionUtil.isEmpty(rule.getSkus()))
        {
            return false;
        }
        return rule.getSkus().stream()
                .filter(Objects::nonNull)
                .map(BillingSku::getInputPricing)
                .filter(Objects::nonNull)
                .anyMatch(p -> Objects.nonNull(image ? p.getImage() : p.getVideo()));
    }

    /** FIXED 固定价表头 */
    private List<BillingColumnVO> buildFixedColumns(boolean perImage)
    {
        List<BillingColumnVO> cols = new ArrayList<>();
        cols.add(new BillingColumnVO("unitPrice", perImage ? "每张单价" : "每次单价", CREDIT_UNIT, "number"));
        cols.add(new BillingColumnVO("remark", "备注", null, "text"));
        return cols;
    }

    /** 模型大类中文名 */
    private String resolveModelTypeName(String type)
    {
        if (Objects.equals(MODEL_TYPE_TEXT, type))
        {
            return "文本生成模型";
        }
        if (Objects.equals(MODEL_TYPE_IMAGE, type))
        {
            return "图片生成模型";
        }
        if (Objects.equals(MODEL_TYPE_VIDEO, type))
        {
            return "视频生成模型";
        }
        if (Objects.equals(MODEL_TYPE_AUDIO, type))
        {
            return "配音模型";
        }
        return type;
    }

    /** 计费口径中文名 */
    private String resolveMeterTypeName(String meterType)
    {
        return switch (meterType)
        {
            case METER_TOKEN -> "按Token阶梯计费";
            case METER_PER_IMAGE -> "按张计费";
            case METER_PER_SECOND -> "按秒计费";
            case METER_SKU_PACKAGE -> "按套餐计费";
            case METER_PER_CHAR -> "按字符计费";
            default -> "固定单价";
        };
    }

    /** 计费整体说明 */
    private String resolveBillingDesc(String meterType)
    {
        return switch (meterType)
        {
            case METER_TOKEN -> "按输入 / 输出 Token 用量计费，根据输入窗口命中对应档位单价结算";
            case METER_PER_IMAGE -> "按实际生成张数计费，每张单价见下方档位";
            case METER_PER_SECOND -> "按生成时长（秒）× 对应分辨率档位的每秒单价计费";
            case METER_SKU_PACKAGE -> "按分辨率 + 时长匹配套餐整包价计费";
            case METER_PER_CHAR -> "按输入文本字符数 × 每字符单价计费";
            default -> "固定单价，每次生成按下方价格扣费";
        };
    }

    /** 按模型大类推断计费口径（无规则 meterType 时兜底） */
    private String inferMeterType(String modelType)
    {
        if (Objects.equals(MODEL_TYPE_TEXT, modelType))
        {
            return METER_TOKEN;
        }
        if (Objects.equals(MODEL_TYPE_IMAGE, modelType))
        {
            return METER_PER_IMAGE;
        }
        if (Objects.equals(MODEL_TYPE_VIDEO, modelType))
        {
            return METER_SKU_PACKAGE;
        }
        return METER_FIXED;
    }

    /** 安全解析 billing_rule_json 为 BillingRule，失败返回 null（仅记录日志，不阻断查询） */
    private BillingRule parseRuleSafely(AidAiModel model)
    {
        try
        {
            return JSONUtil.toBean(model.getBillingRuleJson(), BillingRule.class);
        }
        catch (Exception e)
        {
            log.error("计费详情：解析 billing_rule_json 失败, modelId={}, err={}", model.getId(), e.getMessage());
            return null;
        }
    }

    /** 正数兜底：null 或 <=0 视为 1 */
    private BigDecimal resolvePositive(BigDecimal value)
    {
        if (Objects.nonNull(value) && value.compareTo(BigDecimal.ZERO) > 0)
        {
            return value;
        }
        return BigDecimal.ONE;
    }

    /** 原始价 × 倍率，折算为展示价（去除多余小数尾零，0 直接返回 0） */
    private BigDecimal display(BigDecimal raw, BigDecimal multiplier)
    {
        if (Objects.isNull(raw))
        {
            return BigDecimal.ZERO;
        }
        return scale(raw.multiply(multiplier));
    }

    /** 统一精度：保留 4 位、去尾零；0 返回 BigDecimal.ZERO 避免出现 0E-4 */
    private BigDecimal scale(BigDecimal v)
    {
        if (Objects.isNull(v) || v.compareTo(BigDecimal.ZERO) == 0)
        {
            return BigDecimal.ZERO;
        }
        return v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** 从 match 取 Integer，无值或非数字返回 null */
    private Integer matchIntOrNull(Map<String, Object> match, String key)
    {
        if (CollectionUtil.isEmpty(match) || !match.containsKey(key))
        {
            return null;
        }
        Object v = match.get(key);
        if (v instanceof Number number)
        {
            return number.intValue();
        }
        try
        {
            return Integer.valueOf(String.valueOf(v).trim());
        }
        catch (NumberFormatException ignore)
        {
            return null;
        }
    }
}
