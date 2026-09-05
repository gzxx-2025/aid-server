package com.aid.projectgenconfig.matrix.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidGenAgentPool;
import com.aid.aid.service.IAidGenAgentPoolService;
import com.aid.agent.IAidAgentService;
import com.aid.common.utils.DateUtils;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.CapabilityVO;
import com.aid.model.vo.AiModelVO;
import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.projectgenconfig.matrix.IGenAgentPoolAdminService;
import com.aid.projectgenconfig.matrix.dto.GenPoolSaveCellRequest;
import com.aid.projectgenconfig.matrix.vo.GenPoolCellVO;
import com.aid.projectgenconfig.matrix.vo.GenPoolModelOptionVO;
import com.aid.projectgenconfig.matrix.vo.GenPoolOptionsVO;
import com.aid.projectgenconfig.matrix.vo.SelectOption;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 智能体矩阵后台配置业务Service实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class GenAgentPoolAdminServiceImpl implements IGenAgentPoolAdminService {

    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DEL_FLAG_DELETED = "1";
    private static final String STATUS_ENABLED = "0";
    private static final String IS_DEFAULT_YES = "1";
    private static final String IS_DEFAULT_NO = "0";
    private static final String STRATEGY_ECONOMY = "economy";
    private static final String STRATEGY_PERFORMANCE = "performance";
    /** aid_agent 删除标志：存在（该表 0存在 2软删） */
    private static final String AGENT_DEL_FLAG_NORMAL = "0";
    /** aid_agent 状态：启用 */
    private static final Integer AGENT_STATUS_ENABLED = 1;

    @Autowired
    private IAidGenAgentPoolService aidGenAgentPoolService;

    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Override
    public GenPoolOptionsVO getOptions(String bizCategoryCode) {
        List<SelectOption> agents = new ArrayList<>();
        List<GenPoolModelOptionVO> models = new ArrayList<>();
        if (StrUtil.isNotBlank(bizCategoryCode)) {
            // 该 biz 下启用的智能体
            List<AidAgent> agentList = aidAgentService.list(
                    Wrappers.<AidAgent>lambdaQuery()
                            .eq(AidAgent::getBizCategoryCode, bizCategoryCode)
                            .eq(AidAgent::getStatus, AGENT_STATUS_ENABLED)
                            .eq(AidAgent::getDelFlag, AGENT_DEL_FLAG_NORMAL)
                            .orderByAsc(AidAgent::getId));
            for (AidAgent a : agentList) {
                String label = StrUtil.isBlank(a.getName()) ? a.getAgentCode() : (a.getName() + "（" + a.getAgentCode() + "）");
                agents.add(new SelectOption(a.getAgentCode(), label));
            }
            // 该 biz(func_code) 模型池中的模型
            try {
                List<AiModelVO> modelList = aiModelBusinessService.listAvailableModelsByFuncCode(bizCategoryCode);
                if (CollectionUtil.isNotEmpty(modelList)) {
                    ProjectGenConfigScene scene = ProjectGenConfigScene.fromCode(bizCategoryCode);
                    for (AiModelVO m : modelList) {
                        String label = StrUtil.isBlank(m.getModelName()) ? m.getModelCode() : (m.getModelName() + "（" + m.getModelCode() + "）");
                        models.add(buildModelOption(m, label, scene));
                    }
                }
            } catch (Exception e) {
                log.error("加载模型选项失败: biz={}, err={}", bizCategoryCode, e.getMessage());
            }
        }
        return GenPoolOptionsVO.builder()
                .bizCategoryCode(bizCategoryCode)
                .agents(agents)
                .models(models)
                .build();
    }

    /** 构造模型选项，场景级 sceneRules 优先于模型顶层能力。 */
    private GenPoolModelOptionVO buildModelOption(AiModelVO model, String label, ProjectGenConfigScene scene) {
        CapabilityVO capability = model.getCapability();
        Map<String, Object> sceneRule = null;
        if (Objects.nonNull(capability) && Objects.nonNull(scene)
                && StrUtil.isNotBlank(scene.getCapabilityScene())
                && Objects.nonNull(capability.getSceneRules())) {
            sceneRule = capability.getSceneRules().get(scene.getCapabilityScene());
        }
        List<String> sizeOptions = effectiveOptions(sceneRule, "sizeOptions",
                Objects.isNull(capability) ? null : capability.getSizeOptions());
        List<String> aspectRatioOptions = effectiveOptions(sceneRule, "aspectRatioOptions",
                Objects.isNull(capability) ? null : capability.getAspectRatioOptions());
        String defaultSize = effectiveText(sceneRule, "defaultSize",
                Objects.isNull(capability) ? model.getDefaultSizeCode() :
                        StrUtil.blankToDefault(capability.getDefaultSize(), model.getDefaultSizeCode()));
        String defaultAspectRatio = effectiveText(sceneRule, "defaultAspectRatio",
                Objects.isNull(capability) ? model.getDefaultAspectRatio() :
                        StrUtil.blankToDefault(capability.getDefaultAspectRatio(), model.getDefaultAspectRatio()));
        return GenPoolModelOptionVO.builder()
                .value(model.getModelCode())
                .label(label)
                .sizeOptions(sizeOptions)
                .aspectRatioOptions(aspectRatioOptions)
                .defaultSize(defaultSize)
                .defaultAspectRatio(defaultAspectRatio)
                .supportsSizePreset(effectiveBoolean(sceneRule, "supportsSizePreset", model.getSupportsSizePreset()))
                .supportsAspectRatio(effectiveBoolean(sceneRule, "supportsAspectRatio", model.getSupportsAspectRatio()))
                .build();
    }

    /** 读取场景级字符串数组；场景未配置时回退顶层能力，不在代码中维护固定枚举。 */
    private List<String> effectiveOptions(Map<String, Object> sceneRule, String key, List<String> fallback) {
        Object value = Objects.isNull(sceneRule) ? null : sceneRule.get(key);
        if (value instanceof Iterable<?> iterable) {
            LinkedHashSet<String> options = new LinkedHashSet<>();
            for (Object item : iterable) {
                String option = StrUtil.trim(Objects.toString(item, null));
                if (StrUtil.isNotBlank(option)) {
                    options.add(option);
                }
            }
            if (CollectionUtil.isNotEmpty(options)) {
                return new ArrayList<>(options);
            }
        }
        return CollectionUtil.isEmpty(fallback) ? List.of() : new ArrayList<>(new LinkedHashSet<>(fallback));
    }

    /** 读取场景级文本值。 */
    private String effectiveText(Map<String, Object> sceneRule, String key, String fallback) {
        Object value = Objects.isNull(sceneRule) ? null : sceneRule.get(key);
        return StrUtil.blankToDefault(StrUtil.trim(Objects.toString(value, null)), fallback);
    }

    /** 读取场景级能力开关。 */
    private Boolean effectiveBoolean(Map<String, Object> sceneRule, String key, Boolean fallback) {
        Object value = Objects.isNull(sceneRule) ? null : sceneRule.get(key);
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.TRUE.equals(fallback);
    }

    @Override
    public List<GenPoolCellVO> listMatrix(String step) {
        LambdaQueryWrapper<AidGenAgentPool> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL);
        if (StrUtil.isNotBlank(step)) {
            wrapper.eq(AidGenAgentPool::getStep, step);
        }
        wrapper.orderByAsc(AidGenAgentPool::getStep)
                .orderByAsc(AidGenAgentPool::getBizCategoryCode)
                .orderByAsc(AidGenAgentPool::getCreationMode)
                .orderByAsc(AidGenAgentPool::getScriptType)
                .orderByAsc(AidGenAgentPool::getSortOrder);
        List<AidGenAgentPool> rows = aidGenAgentPoolService.list(wrapper);

        // 按 (step|biz|creationMode|scriptType) 聚合成格子
        Map<String, GenPoolCellVO.GenPoolCellVOBuilder> builderMap = new LinkedHashMap<>();
        Map<String, Set<String>> poolMap = new LinkedHashMap<>();
        for (AidGenAgentPool r : rows) {
            String key = String.join("|", r.getStep(), r.getBizCategoryCode(), r.getCreationMode(), r.getScriptType());
            GenPoolCellVO.GenPoolCellVOBuilder b = builderMap.computeIfAbsent(key, k -> GenPoolCellVO.builder()
                    .step(r.getStep())
                    .bizCategoryCode(r.getBizCategoryCode())
                    .creationMode(r.getCreationMode())
                    .scriptType(r.getScriptType()));
            Set<String> pool = poolMap.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (StrUtil.isNotBlank(r.getAgentCode())) {
                pool.add(r.getAgentCode());
            }
            boolean isDefault = IS_DEFAULT_YES.equals(r.getIsDefault());
            if (STRATEGY_ECONOMY.equals(r.getStrategy()) && isDefault) {
                b.economyAgent(r.getAgentCode()).economyModel(r.getModelCode())
                        .economyResolution(r.getResolution()).economyAspectRatio(r.getAspectRatio());
            } else if (STRATEGY_PERFORMANCE.equals(r.getStrategy()) && isDefault) {
                b.performanceAgent(r.getAgentCode()).performanceModel(r.getModelCode())
                        .performanceResolution(r.getResolution()).performanceAspectRatio(r.getAspectRatio());
            }
        }
        List<GenPoolCellVO> cells = new ArrayList<>();
        for (Map.Entry<String, GenPoolCellVO.GenPoolCellVOBuilder> e : builderMap.entrySet()) {
            cells.add(e.getValue().poolAgents(new ArrayList<>(poolMap.get(e.getKey()))).build());
        }
        return cells;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCell(GenPoolSaveCellRequest request, String operator) {
        validateCell(request);
        LambdaUpdateWrapper<AidGenAgentPool> del = Wrappers.lambdaUpdate();
        del.eq(AidGenAgentPool::getStep, request.getStep())
                .eq(AidGenAgentPool::getBizCategoryCode, request.getBizCategoryCode())
                .eq(AidGenAgentPool::getCreationMode, request.getCreationMode())
                .eq(AidGenAgentPool::getScriptType, request.getScriptType())
                .eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidGenAgentPool::getDelFlag, DEL_FLAG_DELETED)
                .set(AidGenAgentPool::getUpdateBy, operator)
                .set(AidGenAgentPool::getUpdateTime, DateUtils.getNowDate());
        aidGenAgentPoolService.update(del);

        List<AidGenAgentPool> toInsert = new ArrayList<>();
        String economyAgent = StrUtil.trimToNull(request.getEconomyAgent());
        String performanceAgent = StrUtil.trimToNull(request.getPerformanceAgent());
        if (StrUtil.isNotBlank(economyAgent)) {
            toInsert.add(buildRow(request, STRATEGY_ECONOMY, economyAgent,
                    StrUtil.trimToNull(request.getEconomyModel()),
                    StrUtil.trimToNull(request.getEconomyResolution()),
                    StrUtil.trimToNull(request.getEconomyAspectRatio()),
                    IS_DEFAULT_YES, 1, operator));
        }
        if (StrUtil.isNotBlank(performanceAgent)) {
            toInsert.add(buildRow(request, STRATEGY_PERFORMANCE, performanceAgent,
                    StrUtil.trimToNull(request.getPerformanceModel()),
                    StrUtil.trimToNull(request.getPerformanceResolution()),
                    StrUtil.trimToNull(request.getPerformanceAspectRatio()),
                    IS_DEFAULT_YES, 2, operator));
        }
        // 额外候选（去重、排除两个默认）→ 进池（用 economy 占位行，is_default=0）
        Set<String> extra = new LinkedHashSet<>();
        if (CollectionUtil.isNotEmpty(request.getExtraPoolAgents())) {
            for (String a : request.getExtraPoolAgents()) {
                String ac = StrUtil.trim(a);
                if (StrUtil.isNotBlank(ac) && !ac.equals(economyAgent) && !ac.equals(performanceAgent)) {
                    extra.add(ac);
                }
            }
        }
        int sort = 10;
        for (String ac : extra) {
            toInsert.add(buildRow(request, STRATEGY_ECONOMY, ac, null, null, null, IS_DEFAULT_NO, sort++, operator));
        }
        if (CollectionUtil.isNotEmpty(toInsert)) {
            aidGenAgentPoolService.saveBatch(toInsert);
        }
        log.info("保存智能体矩阵格子: biz={}, creationMode={}, scriptType={}, 插入行数={}",
                request.getBizCategoryCode(), request.getCreationMode(), request.getScriptType(), toInsert.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCell(String step, String bizCategoryCode, String creationMode, String scriptType, String operator) {
        LambdaUpdateWrapper<AidGenAgentPool> del = Wrappers.lambdaUpdate();
        del.eq(AidGenAgentPool::getStep, step)
                .eq(AidGenAgentPool::getBizCategoryCode, bizCategoryCode)
                .eq(AidGenAgentPool::getCreationMode, creationMode)
                .eq(AidGenAgentPool::getScriptType, scriptType)
                .eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL)
                .set(AidGenAgentPool::getDelFlag, DEL_FLAG_DELETED)
                .set(AidGenAgentPool::getUpdateBy, operator)
                .set(AidGenAgentPool::getUpdateTime, DateUtils.getNowDate());
        aidGenAgentPoolService.update(del);
    }

    /**
     * 保存前由服务端再次校验智能体与模型成员关系，不能只信任管理端下拉选项。
     */
    private void validateCell(GenPoolSaveCellRequest request) {
        String economyAgent = StrUtil.trimToNull(request.getEconomyAgent());
        String performanceAgent = StrUtil.trimToNull(request.getPerformanceAgent());
        if (StrUtil.isBlank(economyAgent) && StrUtil.isBlank(performanceAgent)) {
            throw new IllegalArgumentException("经济模式和性能模式至少配置一个默认智能体");
        }
        if (StrUtil.isNotBlank(request.getEconomyModel()) && StrUtil.isBlank(economyAgent)) {
            throw new IllegalArgumentException("配置经济模式模型前必须先选择智能体");
        }
        if (StrUtil.isNotBlank(request.getPerformanceModel()) && StrUtil.isBlank(performanceAgent)) {
            throw new IllegalArgumentException("配置性能模式模型前必须先选择智能体");
        }

        GenPoolOptionsVO options = getOptions(request.getBizCategoryCode());
        Set<String> availableAgents = options.getAgents().stream()
                .map(SelectOption::getValue).collect(java.util.stream.Collectors.toSet());
        Map<String, GenPoolModelOptionVO> availableModels = options.getModels().stream()
                .collect(java.util.stream.Collectors.toMap(GenPoolModelOptionVO::getValue, model -> model));

        Set<String> requestedAgents = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(economyAgent)) requestedAgents.add(economyAgent);
        if (StrUtil.isNotBlank(performanceAgent)) requestedAgents.add(performanceAgent);
        if (CollectionUtil.isNotEmpty(request.getExtraPoolAgents())) {
            request.getExtraPoolAgents().stream().map(StrUtil::trim).filter(StrUtil::isNotBlank)
                    .forEach(requestedAgents::add);
        }
        for (String agentCode : requestedAgents) {
            if (!availableAgents.contains(agentCode)) {
                throw new IllegalArgumentException("智能体【" + agentCode + "】未启用或不属于当前业务分类");
            }
        }
        validateModelOption("经济模式", request.getEconomyModel(), request.getEconomyResolution(),
                request.getEconomyAspectRatio(), availableModels);
        validateModelOption("性能模式", request.getPerformanceModel(), request.getPerformanceResolution(),
                request.getPerformanceAspectRatio(), availableModels);
    }

    /** 校验矩阵模型属于 funcCode 模型池，并校验清晰度、比例没有越过模型能力声明。 */
    private void validateModelOption(String label, String modelCode, String resolution, String aspectRatio,
                                     Map<String, GenPoolModelOptionVO> availableModels) {
        String code = StrUtil.trimToNull(modelCode);
        if (StrUtil.isBlank(code)) {
            if (StrUtil.isNotBlank(resolution) || StrUtil.isNotBlank(aspectRatio)) {
                throw new IllegalArgumentException(label + "未选择模型时不能配置清晰度或比例");
            }
            return;
        }
        GenPoolModelOptionVO option = availableModels.get(code);
        if (Objects.isNull(option)) {
            throw new IllegalArgumentException(label + "模型未启用或不属于当前业务模型池");
        }
        if (StrUtil.isNotBlank(resolution) && CollectionUtil.isNotEmpty(option.getSizeOptions())
                && !option.getSizeOptions().contains(resolution)) {
            throw new IllegalArgumentException(label + "清晰度不在模型能力范围内");
        }
        if (StrUtil.isNotBlank(aspectRatio) && CollectionUtil.isNotEmpty(option.getAspectRatioOptions())
                && !option.getAspectRatioOptions().contains(aspectRatio)) {
            throw new IllegalArgumentException(label + "比例不在模型能力范围内");
        }
    }

    /** 构造一条池行 */
    private AidGenAgentPool buildRow(GenPoolSaveCellRequest req, String strategy, String agentCode,
                                     String modelCode, String resolution, String aspectRatio,
                                     String isDefault, int sortOrder, String operator) {
        AidGenAgentPool row = new AidGenAgentPool();
        row.setStep(req.getStep());
        row.setBizCategoryCode(req.getBizCategoryCode());
        row.setCreationMode(req.getCreationMode());
        row.setScriptType(req.getScriptType());
        row.setStrategy(strategy);
        row.setAgentCode(agentCode);
        row.setModelCode(modelCode);
        row.setResolution(resolution);
        row.setAspectRatio(aspectRatio);
        row.setIsDefault(isDefault);
        row.setSortOrder(sortOrder);
        row.setStatus(STATUS_ENABLED);
        row.setDelFlag(DEL_FLAG_NORMAL);
        row.setCreateBy(operator);
        row.setCreateTime(DateUtils.getNowDate());
        return row;
    }
}
