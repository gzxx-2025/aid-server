package com.aid.model.definition;

import com.aid.aid.domain.AidAiBusinessModelBinding;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelCapability;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.mapper.AidAiBusinessModelBindingMapper;
import com.aid.aid.mapper.AidAiModelCapabilityMapper;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFunctionBindingReconciler {
    private final AidAiBusinessModelBindingMapper bindings;
    private final AidAiModelCapabilityMapper capabilities;

    /** 功能池的所有写入入口共用此方法；多能力模型必须明确选择业务能力。 */
    @Transactional(rollbackFor = Exception.class)
    public void reconcile(AidAiModelFuncConfig function, String actor) {
        List<Long> ids = function.getModelIds() == null ? List.of() : JSON.parseArray(function.getModelIds(), Long.class);
        List<AidAiBusinessModelBinding> previous = bindings.selectList(Wrappers.<AidAiBusinessModelBinding>lambdaQuery()
                .eq(AidAiBusinessModelBinding::getFuncCode, function.getFuncCode()));
        List<AidAiBusinessModelBinding> selected = new ArrayList<>();
        for (Long id : ids) {
            List<ModelCapabilityDefinition> definitions = capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery()
                    .eq(AidAiModelCapability::getModelId, id)).stream()
                    .map(row -> JSON.parseObject(row.getDefinitionJson(), ModelCapabilityDefinition.class)).toList();
            if (definitions.isEmpty()) continue;
            List<AidAiBusinessModelBinding> rows = (function.getModelBindings() == null ? previous : function.getModelBindings())
                    .stream().filter(row -> Objects.equals(row.getModelId(), id)).toList();
            if (rows.isEmpty() && function.getModelBindings() == null) {
                List<ModelCapabilityDefinition> candidates = definitions.stream().filter(d -> Boolean.TRUE.equals(d.getEnabled()))
                        .filter(d -> function.getGenerateMode() == null || function.getGenerateMode().isBlank()
                                || Objects.equals(d.getGenerateMode(), function.getGenerateMode())).toList();
                if (candidates.size() == 1) {
                    AidAiBusinessModelBinding row = new AidAiBusinessModelBinding();
                    row.setModelId(id); row.setCapabilityCode(candidates.get(0).getCode()); row.setDefaultCapability(true);
                    rows = List.of(row);
                }
            }
            if (rows.isEmpty() || rows.stream().filter(row -> Boolean.TRUE.equals(row.getDefaultCapability())).count() != 1)
                fail("请在功能配置中选择模型能力和默认能力");
            Set<String> codes = new LinkedHashSet<>();
            for (AidAiBusinessModelBinding row : rows) {
                ModelCapabilityDefinition definition = definitions.stream()
                        .filter(d -> Boolean.TRUE.equals(d.getEnabled()) && Objects.equals(d.getCode(), row.getCapabilityCode())).findFirst().orElse(null);
                if (definition == null || !codes.add(row.getCapabilityCode())) fail("业务能力不可用或重复");
                if (row.getDefaultsJson() != null && !row.getDefaultsJson().isBlank())
                    ModelParameterValidator.validateBusinessDefaults(definition, JSON.parseObject(row.getDefaultsJson()));
                AidAiBusinessModelBinding copy = new AidAiBusinessModelBinding();
                copy.setModelId(id); copy.setFuncCode(function.getFuncCode()); copy.setCapabilityCode(row.getCapabilityCode());
                copy.setDefaultCapability(row.getDefaultCapability()); copy.setDefaultsJson(row.getDefaultsJson());
                copy.setSortOrder(selected.size()); copy.setCreateBy(actor); copy.setCreateTime(DateUtils.getNowDate()); selected.add(copy);
            }
        }
        bindings.delete(Wrappers.<AidAiBusinessModelBinding>lambdaQuery().eq(AidAiBusinessModelBinding::getFuncCode, function.getFuncCode()));
        selected.forEach(bindings::insert);
    }

    private static void fail(String message) { log.info("业务模型绑定失败: {}", message); throw new ServiceException(message); }
}
