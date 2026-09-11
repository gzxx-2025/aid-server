package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import com.aid.aid.domain.AidAiBusinessModelBinding;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.AidAiModelCapability;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.AidAiModelMigration;
import com.aid.aid.domain.AidAiModelProtocolBinding;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.mapper.AidAiBusinessModelBindingMapper;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.mapper.AidAiModelCapabilityMapper;
import com.aid.aid.mapper.AidAiModelFuncConfigMapper;
import com.aid.aid.mapper.AidAiModelMapper;
import com.aid.aid.mapper.AidAiModelMigrationMapper;
import com.aid.aid.mapper.AidAiModelProtocolBindingMapper;
import com.aid.aid.mapper.AidAiVoiceLibraryMapper;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 事务内记录配置变更；回滚前逐项比较后置状态，不覆盖后续编辑。 */
@Service
@RequiredArgsConstructor
public class ModelMigrationJournal {
    private final AidAiModelMigrationMapper journal;
    private final AidAiModelMapper models;
    private final AidAiModelCapabilityMapper capabilities;
    private final AidAiModelProtocolBindingMapper routes;
    private final AidAiModelAliasMapper aliases;
    private final AidAiBusinessModelBindingMapper businesses;
    private final AidAiModelFuncConfigMapper functions;
    private final AidAiVoiceLibraryMapper voices;
    private final ModelMigrationReferences references;

    @Data
    public static class Snapshot {
        private List<Long> modelIds;
        private List<AidAiModel> models;
        private List<AidAiModelCapability> capabilities;
        private List<AidAiModelProtocolBinding> routes;
        private List<AidAiModelAlias> aliases;
        private List<AidAiBusinessModelBinding> businesses;
        private List<AidAiModelFuncConfig> functions;
        private List<AidAiVoiceLibrary> voices;
        private Map<String, List<Map<String, Object>>> references;
    }

    public AidAiModelMigration find(String key) {
        return journal.selectOne(Wrappers.<AidAiModelMigration>lambdaQuery().eq(AidAiModelMigration::getRequestKey, key).last("FOR UPDATE"));
    }

    public List<AidAiModelMigration> recent() {
        return journal.selectList(Wrappers.<AidAiModelMigration>lambdaQuery()
                .select(AidAiModelMigration::getId, AidAiModelMigration::getStatus, AidAiModelMigration::getAffectedModels,
                        AidAiModelMigration::getCreateTime, AidAiModelMigration::getCreateBy,
                        AidAiModelMigration::getUpdateTime, AidAiModelMigration::getUpdateBy)
                .orderByDesc(AidAiModelMigration::getId).last("LIMIT 50"));
    }

    public String capture(List<Long> ids) {
        Snapshot snapshot = new Snapshot();
        snapshot.setModelIds(ids.stream().sorted().toList());
        snapshot.setModels(models.selectList(Wrappers.<AidAiModel>lambdaQuery().in(AidAiModel::getId, ids).orderByAsc(AidAiModel::getId)));
        snapshot.setCapabilities(capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery().in(AidAiModelCapability::getModelId, ids).orderByAsc(AidAiModelCapability::getId)));
        snapshot.setRoutes(routes.selectList(Wrappers.<AidAiModelProtocolBinding>lambdaQuery().in(AidAiModelProtocolBinding::getModelId, ids).orderByAsc(AidAiModelProtocolBinding::getId)));
        snapshot.setAliases(aliases.selectList(Wrappers.<AidAiModelAlias>lambdaQuery().in(AidAiModelAlias::getModelId, ids).orderByAsc(AidAiModelAlias::getId)));
        snapshot.setBusinesses(businesses.selectList(Wrappers.<AidAiBusinessModelBinding>lambdaQuery().in(AidAiBusinessModelBinding::getModelId, ids).orderByAsc(AidAiBusinessModelBinding::getId)));
        snapshot.setFunctions(functions.selectList(Wrappers.<AidAiModelFuncConfig>lambdaQuery().orderByAsc(AidAiModelFuncConfig::getId).last("FOR UPDATE")).stream()
                .filter(row -> row.getModelIds() != null && JSON.parseArray(row.getModelIds(), Long.class).stream().anyMatch(ids::contains)).toList());
        snapshot.setVoices(voices.selectList(Wrappers.<AidAiVoiceLibrary>lambdaQuery().in(AidAiVoiceLibrary::getModelId, ids).orderByAsc(AidAiVoiceLibrary::getId).last("FOR UPDATE")));
        snapshot.setReferences(references.capture(snapshot.getModels()));
        return JSON.toJSONString(snapshot, JSONWriter.Feature.WriteMapNullValue);
    }

    public void record(String key, String digest, String before, String after, int count, String actor) {
        AidAiModelMigration entry = new AidAiModelMigration();
        entry.setRequestKey(key); entry.setRequestDigest(digest); entry.setStatus("APPLIED");
        entry.setBeforeJson(before); entry.setAfterJson(after); entry.setAffectedModels(count);
        entry.setCreateBy(actor); entry.setCreateTime(DateUtils.getNowDate()); journal.insert(entry);
    }

    @Transactional(rollbackFor = Exception.class)
    public int rollback(Long id, String actor) {
        AidAiModelMigration entry = journal.selectOne(Wrappers.<AidAiModelMigration>lambdaQuery().eq(AidAiModelMigration::getId, id).last("FOR UPDATE"));
        if (entry == null) throw new ServiceException("迁移记录不存在");
        if ("ROLLED_BACK".equals(entry.getStatus())) return entry.getAffectedModels();
        if (!"APPLIED".equals(entry.getStatus())) throw new ServiceException("迁移状态不支持回滚");
        Snapshot before = JSON.parseObject(entry.getBeforeJson(), Snapshot.class);
        Snapshot after = JSON.parseObject(entry.getAfterJson(), Snapshot.class);
        models.selectList(Wrappers.<AidAiModel>lambdaQuery().in(AidAiModel::getId, before.getModelIds()).orderByAsc(AidAiModel::getId).last("FOR UPDATE"));
        if (!Objects.equals(JSON.parse(entry.getAfterJson()), JSON.parse(capture(before.getModelIds()))))
            throw new ServiceException("迁移后配置已有变化，请先核对差异，不能直接回滚");
        for (AidAiModel original : before.getModels()) {
            long currentVersion = after.getModels().stream().filter(row -> Objects.equals(row.getId(), original.getId()))
                    .map(row -> row.getConfigVersion() == null ? 0L : row.getConfigVersion()).findFirst().orElse(0L);
            original.setConfigVersion(currentVersion + 1);
            original.setUpdateBy(actor); original.setUpdateTime(DateUtils.getNowDate());
            restore(models, original, AidAiModel.class);
        }
        capabilities.delete(Wrappers.<AidAiModelCapability>lambdaQuery().in(AidAiModelCapability::getModelId, before.getModelIds()));
        routes.delete(Wrappers.<AidAiModelProtocolBinding>lambdaQuery().in(AidAiModelProtocolBinding::getModelId, before.getModelIds()));
        aliases.delete(Wrappers.<AidAiModelAlias>lambdaQuery().in(AidAiModelAlias::getModelId, before.getModelIds()));
        businesses.delete(Wrappers.<AidAiBusinessModelBinding>lambdaQuery().in(AidAiBusinessModelBinding::getModelId, before.getModelIds()));
        before.getCapabilities().forEach(capabilities::insert);
        before.getRoutes().forEach(routes::insert);
        before.getAliases().forEach(aliases::insert);
        before.getBusinesses().forEach(businesses::insert);
        for (var function : before.getFunctions()) restore(functions, function, AidAiModelFuncConfig.class);
        for (var voice : before.getVoices()) restore(voices, voice, AidAiVoiceLibrary.class);
        references.restore(before.getReferences());
        entry.setStatus("ROLLED_BACK");
        entry.setUpdateBy(actor); entry.setUpdateTime(DateUtils.getNowDate()); journal.updateById(entry);
        return entry.getAffectedModels();
    }

    private static <T> void restore(BaseMapper<T> mapper, T row, Class<T> type) {
        var table = TableInfoHelper.getTableInfo(type);
        UpdateWrapper<T> update = new UpdateWrapper<>();
        update.eq(table.getKeyColumn(), BeanUtil.getProperty(row, table.getKeyProperty()));
        // 元数据只包含持久化字段；显式恢复 NULL，不能用忽略空值的局部更新。
        table.getFieldList().forEach(field -> update.set(field.getColumn(), BeanUtil.getProperty(row, field.getProperty())));
        mapper.update(null, update);
    }
}
