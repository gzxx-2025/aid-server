package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.service.IAidAiModelService;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 在检查业务池之前统一解析历史模型标识。 */
@Service
@RequiredArgsConstructor
public class BusinessModelResolver {
    private final IAidAiModelService models;
    private final ModelDefinitionService definitions;
    private final ModelBusinessBindingService bindings;

    public AidAiModel resolve(String functionCode, String modelCode, String expectedType) {
        var alias = definitions.alias(modelCode);
        AidAiModel model = alias == null ? models.getOne(Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelCode, modelCode).eq(AidAiModel::getDelFlag, "0")) : models.getById(alias.getModelId());
        if (model == null || !"0".equals(model.getStatus()) || !"0".equals(model.getDelFlag())) throw new ServiceException("模型不可用");
        if (!Objects.equals(expectedType, model.getModelType())) throw new ServiceException("业务模型类型不符");
        String capability = bindings.capability(model.getId(), functionCode, null);
        if (definitions.definitions(model.getId()).isEmpty()) return model;
        String defaults = bindings.forFunction(functionCode).stream()
                .filter(binding -> Objects.equals(binding.getModelId(), model.getId())
                        && Objects.equals(binding.getCapabilityCode(), capability))
                .map(binding -> binding.getDefaultsJson() == null ? "{}" : binding.getDefaultsJson())
                .findFirst().orElse(null);
        AidAiModel projected = definitions.project(model, capability, defaults);
        if (projected == null) throw new ServiceException("业务模型能力不可用");
        return projected;
    }
}
