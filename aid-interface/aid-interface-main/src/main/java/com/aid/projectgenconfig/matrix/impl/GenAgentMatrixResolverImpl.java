package com.aid.projectgenconfig.matrix.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidGenAgentPool;
import com.aid.aid.service.IAidGenAgentPoolService;
import com.aid.projectgenconfig.matrix.GenAgentMatrixResult;
import com.aid.projectgenconfig.matrix.IGenAgentMatrixResolver;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 智能体矩阵解析器实现（读取 aid_gen_agent_pool）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class GenAgentMatrixResolverImpl implements IGenAgentMatrixResolver {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 状态：正常/启用 */
    private static final String STATUS_ENABLED = "0";

    /** 默认标记：是 */
    private static final String IS_DEFAULT_YES = "1";

    /** 维度通配符：非分镜场景的 creation_mode/script_type 取值，命中任意维度 */
    private static final String WILDCARD = "*";

    @Autowired
    private IAidGenAgentPoolService aidGenAgentPoolService;

    @Override
    public GenAgentMatrixResult resolve(String bizCategoryCode, String creationMode, String scriptType, String strategy) {
        // 可选池：忽略剧本类型（同一 biz × 创作模式 下，剧情演绎/真人解说各版本均纳入，都可选）
        List<AidGenAgentPool> poolRows = listEnabledRows(bizCategoryCode, creationMode, null);
        if (CollectionUtil.isEmpty(poolRows)) {
            // 未配置：该场景不适用于当前创作模式（如 i2v 无多参视频提示词）
            return GenAgentMatrixResult.builder()
                    .bizCategoryCode(bizCategoryCode)
                    .agentPool(new ArrayList<>())
                    .configured(false)
                    .build();
        }
        // 可选池：去重后的全部 agentCode（不区分策略、不区分剧本类型）
        Set<String> pool = new LinkedHashSet<>();
        for (AidGenAgentPool r : poolRows) {
            if (StrUtil.isNotBlank(r.getAgentCode())) {
                pool.add(r.getAgentCode());
            }
        }
        // 默认项：仍按 剧本类型 + 策略 取该组合 is_default=1 的行（保证一键/批量生成默认值合理）；
        // 缺失则回退该剧本类型下任一策略行。
        List<AidGenAgentPool> defaultRows = listEnabledRows(bizCategoryCode, creationMode, scriptType);
        AidGenAgentPool def = defaultRows.stream()
                .filter(r -> Objects.equals(strategy, r.getStrategy()) && IS_DEFAULT_YES.equals(r.getIsDefault()))
                .findFirst()
                .orElseGet(() -> defaultRows.stream()
                        .filter(r -> Objects.equals(strategy, r.getStrategy()))
                        .findFirst()
                        .orElse(null));
        return GenAgentMatrixResult.builder()
                .bizCategoryCode(bizCategoryCode)
                .agentCode(def == null ? null : def.getAgentCode())
                .modelCode(def == null ? null : def.getModelCode())
                .resolution(def == null ? null : def.getResolution())
                .aspectRatio(def == null ? null : def.getAspectRatio())
                .agentPool(new ArrayList<>(pool))
                .configured(true)
                .build();
    }

    @Override
    public List<String> listAgentPool(String bizCategoryCode, String creationMode, String scriptType) {
        // 池忽略剧本类型：剧情演绎/真人解说各版本均可选
        List<AidGenAgentPool> rows = listEnabledRows(bizCategoryCode, creationMode, null);
        Set<String> pool = new LinkedHashSet<>();
        for (AidGenAgentPool r : rows) {
            if (StrUtil.isNotBlank(r.getAgentCode())) {
                pool.add(r.getAgentCode());
            }
        }
        return new ArrayList<>(pool);
    }

    @Override
    public boolean isAgentAllowed(String bizCategoryCode, String creationMode, String scriptType, String agentCode) {
        if (StrUtil.isBlank(agentCode)) {
            return false;
        }
        return listAgentPool(bizCategoryCode, creationMode, scriptType).contains(agentCode.trim());
    }

    @Override
    public boolean isAgentInScenePool(String bizCategoryCode, String agentCode) {
        if (StrUtil.isBlank(bizCategoryCode) || StrUtil.isBlank(agentCode)) {
            return false;
        }
        // 仅按 业务场景 + 智能体 匹配，忽略创作模式/剧本类型/策略：只要矩阵里该场景挂过此智能体即视为合法候选
        List<AidGenAgentPool> rows = aidGenAgentPoolService.list(
                Wrappers.<AidGenAgentPool>lambdaQuery()
                        .eq(AidGenAgentPool::getBizCategoryCode, bizCategoryCode)
                        .eq(AidGenAgentPool::getAgentCode, agentCode.trim())
                        .eq(AidGenAgentPool::getStatus, STATUS_ENABLED)
                        .eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL)
                        .last("limit 1"));
        return CollectionUtil.isNotEmpty(rows);
    }

    @Override
    public Map<String, List<String>> listManagedScenePools(Collection<String> bizCategoryCodes, String creationMode) {
        Map<String, List<String>> resultMap = new HashMap<>();
        if (CollectionUtil.isEmpty(bizCategoryCodes)) {
            return resultMap;
        }
        // 去重 + 去空
        Set<String> bizSet = new LinkedHashSet<>();
        for (String b : bizCategoryCodes) {
            if (StrUtil.isNotBlank(b)) {
                bizSet.add(b.trim());
            }
        }
        if (bizSet.isEmpty()) {
            return resultMap;
        }
        // 一次查出这些场景的全部启用行（不限创作模式/剧本类型/策略），按 sortOrder/id 排序，
        // 既用于判定「是否受管」（有行即受管），也在内存里取当前创作模式可选池，避免 N+1。
        List<AidGenAgentPool> rows = aidGenAgentPoolService.list(
                Wrappers.<AidGenAgentPool>lambdaQuery()
                        .select(AidGenAgentPool::getBizCategoryCode, AidGenAgentPool::getCreationMode,
                                AidGenAgentPool::getAgentCode)
                        .in(AidGenAgentPool::getBizCategoryCode, bizSet)
                        .eq(AidGenAgentPool::getStatus, STATUS_ENABLED)
                        .eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidGenAgentPool::getSortOrder)
                        .orderByAsc(AidGenAgentPool::getId));
        String cm = StrUtil.trimToEmpty(creationMode);
        // 受管 biz → 当前模式池（有序去重）；creation_mode 命中 当前模式 或 通配 '*'，忽略剧本类型
        Map<String, LinkedHashSet<String>> tmp = new LinkedHashMap<>();
        for (AidGenAgentPool r : rows) {
            String biz = r.getBizCategoryCode();
            // 有行即标记受管（即使当前模式无匹配，也建空集合 → 受管但不适用）
            LinkedHashSet<String> pool = tmp.computeIfAbsent(biz, k -> new LinkedHashSet<>());
            if (StrUtil.isNotBlank(r.getAgentCode())
                    && (Objects.equals(cm, r.getCreationMode()) || WILDCARD.equals(r.getCreationMode()))) {
                pool.add(r.getAgentCode());
            }
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : tmp.entrySet()) {
            resultMap.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return resultMap;
    }

    /**
     * 查询某 (biz, creationMode, scriptType) 下全部启用、未删除的池行。
     */
    private List<AidGenAgentPool> listEnabledRows(String bizCategoryCode, String creationMode, String scriptType) {
        if (StrUtil.isBlank(bizCategoryCode)) {
            return new ArrayList<>();
        }
        // scriptType 为空 = 忽略剧本类型（可选池场景），不加该过滤；非空才按 [scriptType, '*'] 匹配
        boolean filterScriptType = StrUtil.isNotBlank(scriptType);
        LambdaQueryWrapper<AidGenAgentPool> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidGenAgentPool::getBizCategoryCode, bizCategoryCode)
                .in(AidGenAgentPool::getCreationMode, candidates(creationMode))
                .in(filterScriptType, AidGenAgentPool::getScriptType, candidates(scriptType))
                .eq(AidGenAgentPool::getStatus, STATUS_ENABLED)
                .eq(AidGenAgentPool::getDelFlag, DEL_FLAG_NORMAL)
                .orderByAsc(AidGenAgentPool::getSortOrder)
                .orderByAsc(AidGenAgentPool::getId);
        return aidGenAgentPoolService.list(wrapper);
    }

    /** 维度候选值：含通配 {@code *}；输入为空时仅匹配通配行。 */
    private List<String> candidates(String value) {
        if (StrUtil.isBlank(value)) {
            return java.util.Collections.singletonList(WILDCARD);
        }
        return java.util.Arrays.asList(value.trim(), WILDCARD);
    }
}
