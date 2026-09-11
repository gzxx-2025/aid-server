package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.mapper.AidAgentMapper;
import com.aid.aid.mapper.AidAudioAssetMapper;
import com.aid.aid.mapper.AidGenAgentPoolMapper;
import com.aid.aid.mapper.AidProviderErrorRuleMapper;
import com.aid.aid.mapper.AidRoleVoiceBindingMapper;
import com.aid.skill.mapper.AidSkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 仅迁移当前业务配置的模型引用列，不读取提示词、历史记录或已发布快照。 */
@Service
@RequiredArgsConstructor
public class ModelMigrationReferences {
    private final AidAgentMapper agents;
    private final AidGenAgentPoolMapper pools;
    private final AidRoleVoiceBindingMapper roles;
    private final AidAudioAssetMapper assets;
    private final AidSkillMapper skills;
    private final AidProviderErrorRuleMapper errorRules;

    private record Reference(BaseMapper<?> mapper, String field, boolean numeric) { }
    private Map<String, Reference> stores() {
        Map<String, Reference> result = new LinkedHashMap<>();
        result.put("agents", new Reference(agents, "model_code", false));
        result.put("agentPools", new Reference(pools, "model_code", false));
        result.put("roleVoices", new Reference(roles, "model_id", true));
        result.put("audioAssets", new Reference(assets, "voice_model_id", true));
        result.put("skills", new Reference(skills, "model_code", false));
        result.put("errorRules", new Reference(errorRules, "model_code", false));
        return result;
    }

    public Map<String, List<Map<String, Object>>> capture(List<AidAiModel> models) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        stores().forEach((name, store) -> result.put(name, read(store, store.numeric()
                ? models.stream().map(AidAiModel::getId).toList() : models.stream().map(AidAiModel::getModelCode).toList())));
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> read(Reference store, List<?> identifiers) {
        return ((BaseMapper) store.mapper()).selectMaps(new QueryWrapper<>().select("id", store.field())
                .in(store.field(), identifiers).orderByAsc("id").last("FOR UPDATE"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void migrate(AidAiModel original, AidAiModel target) {
        stores().values().forEach(store -> ((BaseMapper) store.mapper()).update(null, new UpdateWrapper<>()
                .eq(store.field(), store.numeric() ? original.getId() : original.getModelCode())
                .set(store.field(), store.numeric() ? target.getId() : target.getModelCode())));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void restore(Map<String, List<Map<String, Object>>> snapshot) {
        stores().forEach((name, store) -> {
            for (var row : snapshot.getOrDefault(name, List.of())) ((BaseMapper) store.mapper()).update(null,
                    new UpdateWrapper<>().eq("id", row.get("id")).set(store.field(), row.get(store.field())));
        });
    }
}
