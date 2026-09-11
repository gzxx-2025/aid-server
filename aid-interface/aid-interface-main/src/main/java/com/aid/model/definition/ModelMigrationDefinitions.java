package com.aid.model.definition;

import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.alibaba.fastjson2.JSON;
import java.util.List;

/** 合并时给源记录的协议建立稳定命名空间，避免相同协议编码互相覆盖。 */
public final class ModelMigrationDefinitions {
    private ModelMigrationDefinitions() { }
    public static List<ModelCapabilityDefinition> source(AidAiModel model, List<ModelCapabilityDefinition> configured) {
        return configured.isEmpty() ? LegacyModelDefinitionConverter.convert(model)
                : JSON.parseArray(JSON.toJSONString(configured), ModelCapabilityDefinition.class);
    }
    public static String routeCode(Long modelId, String original, boolean merge) {
        return merge ? "model_" + modelId + "_" + SecureUtil.sha256(original).substring(0, 16) : original;
    }
}
