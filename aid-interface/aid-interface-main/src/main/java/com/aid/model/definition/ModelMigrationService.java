package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiBusinessModelBinding;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 预检并事务化合并已核验的同供应商模型。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelMigrationService {
    private static final List<String> OPERATIONAL_FIELDS = List.of("status", "billingMultiplier", "priority", "scheduleStrategyJson", "isFree");
    private final IAidAiModelService models;
    private final ModelDefinitionService definitions;
    private final AidAiModelAliasMapper aliases;
    private final IAidAiModelFuncConfigService functions;
    private final ModelBusinessBindingService businessBindings;
    private final IAidAiVoiceLibraryService voices;
    private final IAidMediaTaskService tasks;
    private final ModelMigrationJournal journal;
    private final ModelMigrationReferences references;

    @Data
    public static class Group {
        private Long providerId;
        private String upstreamIdentity;
        private List<AidAiModel> models;
        private List<String> conflicts;
        private List<ModelCapabilityDefinition> capabilities;
        private String identityNote;
        private List<AidAiBusinessModelBinding> businessBindings;
        private Map<Long, List<ModelCapabilityDefinition>> definitionSources;
        private Map<Long, List<AidAiBusinessModelBinding>> businessBindingSources;
        private List<String> capabilityConflicts;
        private List<String> businessDefaultsConflicts;
    }

    @Data
    public static class MigrationRequest {
        private String requestKey;
        private List<Decision> decisions;
    }

    @Data
    public static class Decision {
        private List<Long> modelIds;
        private Map<Long, Long> expectedVersions;
        private Long canonicalModelId;
        private Long operationalModelId;
        private String modelName;
        private Boolean identityVerified;
        private List<ModelCapabilityDefinition> capabilities;
        private List<AidAiBusinessModelBinding> businessBindings;
        private Map<String, Long> capabilitySources;
        private Map<String, Long> businessDefaultSources;
    }

    public List<Group> preview() {
        List<AidAiModel> all = models.list(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getDelFlag, "0").orderByAsc(AidAiModel::getId));
        Map<String, List<AidAiModel>> grouped = new LinkedHashMap<>();
        for (AidAiModel model : all) {
            String identity = model.getRealModelCode() == null || model.getRealModelCode().isBlank() ? model.getModelCode() : model.getRealModelCode();
            // 对口型旧配置可能借用了视频模型代码；缺少上游 model 参数的服务不能据此认定同一模型。
            if ("lip_sync".equals(LegacyModelDefinitionConverter.primaryCode(model))) identity = "service/" + model.getModelCode();
            grouped.computeIfAbsent(model.getProviderId() + "/" + identity, key -> new ArrayList<>()).add(model);
        }
        List<Group> result = new ArrayList<>();
        for (List<AidAiModel> members : grouped.values()) {
            Group group = new Group();
            group.setProviderId(members.get(0).getProviderId());
            group.setUpstreamIdentity(members.get(0).getRealModelCode());
            group.setIdentityNote("lip_sync".equals(LegacyModelDefinitionConverter.primaryCode(members.get(0)))
                    ? "独立对口型服务：当前文档未证明与所填视频模型相同，请核实服务标识，不与视频生成模型自动合并。"
                    : "仅按现有真实模型标识提出候选；请核对原厂模型、实际版本与渠道路由后确认。" );
            group.setModels(members);
            group.setConflicts(OPERATIONAL_FIELDS.stream().filter(field -> members.stream()
                    .map(m -> JSON.toJSONString(BeanUtil.getProperty(m, field))).distinct().count() > 1).toList());
            Map<String, ModelCapabilityDefinition> combined = new LinkedHashMap<>();
            group.setDefinitionSources(new LinkedHashMap<>());
            group.setBusinessBindingSources(new LinkedHashMap<>());
            group.setCapabilityConflicts(new ArrayList<>());
            group.setBusinessDefaultsConflicts(new ArrayList<>());
            for (AidAiModel member : members) {
                List<ModelCapabilityDefinition> current = ModelMigrationDefinitions.source(member, definitions.definitions(member.getId()));
                group.getDefinitionSources().put(member.getId(), JSON.parseArray(JSON.toJSONString(current), ModelCapabilityDefinition.class));
                for (ModelCapabilityDefinition definition : current) {
                    definition.getBindings().forEach(route -> route.setCode(ModelMigrationDefinitions.routeCode(member.getId(), route.getCode(), members.size() > 1)));
                    ModelCapabilityDefinition existing = combined.get(definition.getCode());
                    if (existing == null) {
                        if (members.size() > 1) definition.setDefaultCapability(false);
                        combined.put(definition.getCode(), definition);
                    } else {
                        if (!Objects.equals(JSON.toJSONString(existing.getParameters()), JSON.toJSONString(definition.getParameters()))
                                || !Objects.equals(JSON.toJSONString(existing.getRules()), JSON.toJSONString(definition.getRules()))) {
                            List<String> conflicts = new ArrayList<>(group.getConflicts());
                            conflicts.add("能力 " + definition.getCode() + " 的参数或条件规则不同，请逐项核对");
                            if (!group.getCapabilityConflicts().contains(definition.getCode())) group.getCapabilityConflicts().add(definition.getCode());
                            group.setConflicts(conflicts);
                        }
                        List<ModelProtocolBinding> routes = new ArrayList<>(existing.getBindings());
                        routes.addAll(definition.getBindings());
                        routes.forEach(route -> route.setDefaultBinding(false));
                        existing.setBindings(routes);
                    }
                }
            }
            group.setCapabilities(new ArrayList<>(combined.values()));
            Map<String, AidAiBusinessModelBinding> business = new LinkedHashMap<>();
            for (AidAiModel member : members) {
                List<AidAiBusinessModelBinding> configured = businessBindings.forModel(member.getId());
                if (configured.isEmpty()) configured = businessBindings.legacyBindings(member);
                group.getBusinessBindingSources().put(member.getId(), JSON.parseArray(JSON.toJSONString(configured), AidAiBusinessModelBinding.class));
                for (var row : configured) {
                    String key = row.getFuncCode() + "/" + row.getCapabilityCode();
                    var prior = business.putIfAbsent(key, row);
                    if (prior != null && !Objects.equals(prior.getDefaultsJson(), row.getDefaultsJson())) {
                        var conflicts = new ArrayList<>(group.getConflicts());
                        conflicts.add("业务 " + row.getFuncCode() + " 的默认参数不同，请选择统一值"); group.setConflicts(conflicts);
                        if (!group.getBusinessDefaultsConflicts().contains(key)) group.getBusinessDefaultsConflicts().add(key);
                    }
                }
            }
            if (members.size() > 1) business.values().forEach(row -> row.setDefaultCapability(false));
            group.setBusinessBindings(new ArrayList<>(business.values()));
            result.add(group);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public int apply(MigrationRequest request, String actor) {
        if (request == null || request.getDecisions() == null || request.getDecisions().isEmpty()) fail("请选择迁移模型");
        if (request.getRequestKey() == null || !request.getRequestKey().matches("[A-Za-z0-9_-]{16,80}")) fail("迁移请求标识无效");
        Set<Long> unique = new LinkedHashSet<>();
        for (Decision decision : request.getDecisions()) {
            if (decision.getModelIds() == null || decision.getModelIds().isEmpty()
                    || !Boolean.TRUE.equals(decision.getIdentityVerified()) || decision.getExpectedVersions() == null) fail("请先核验模型身份");
            for (Long id : decision.getModelIds()) if (!unique.add(id)) fail("迁移模型重复");
        }
        models.list(Wrappers.<AidAiModel>lambdaQuery().in(AidAiModel::getId, unique).orderByAsc(AidAiModel::getId).last("FOR UPDATE"));
        String digest = SecureUtil.sha256(JSON.toJSONString(request.getDecisions()));
        var prior = journal.find(request.getRequestKey());
        if (prior != null) {
            if (!Objects.equals(prior.getRequestDigest(), digest) || !"APPLIED".equals(prior.getStatus())) fail("迁移请求已变更，请重新预检");
            return prior.getAffectedModels();
        }
        String before = journal.capture(new ArrayList<>(unique));
        int count = 0;
        for (Decision decision : request.getDecisions()) count += applyGroup(decision, actor);
        journal.record(request.getRequestKey(), digest, before, journal.capture(new ArrayList<>(unique)), count, actor);
        return count;
    }

    private int applyGroup(Decision decision, String actor) {
        List<AidAiModel> members = models.list(Wrappers.<AidAiModel>lambdaQuery().in(AidAiModel::getId, decision.getModelIds())
                .eq(AidAiModel::getDelFlag, "0").orderByAsc(AidAiModel::getId).last("FOR UPDATE"));
        if (members.size() != decision.getModelIds().size()) fail("模型已变请重新预检");
        AidAiModel canonical = members.stream().filter(m -> Objects.equals(m.getId(), decision.getCanonicalModelId())).findFirst().orElse(null);
        AidAiModel operation = members.stream().filter(m -> Objects.equals(m.getId(), decision.getOperationalModelId())).findFirst().orElse(null);
        if (canonical == null || operation == null || decision.getModelName() == null || decision.getModelName().isBlank()) fail("迁移配置不完整");
        if (members.size() > 1 && (canonical.getRealModelCode() == null || canonical.getRealModelCode().isBlank())) fail("缺少真实模型标识，不能合并");
        for (AidAiModel model : members) {
            if (!Objects.equals(model.getProviderId(), canonical.getProviderId()) || !Objects.equals(model.getModelType(), canonical.getModelType())) fail("不能跨供应商合并");
            if (members.size() > 1 && (!Objects.equals(model.getRealModelCode(), canonical.getRealModelCode())
                    || "lip_sync".equals(LegacyModelDefinitionConverter.primaryCode(model)))) fail("模型身份不一致，请单独核验");
            if (!Objects.equals(model.getConfigVersion() == null ? 0L : model.getConfigVersion(), decision.getExpectedVersions().get(model.getId()))) fail("配置已变请重新预检");
        }
        // 先阻止仍无快照的在途任务迁移，避免用合并后的协议查询原任务；完成后可原样重试。
        long running = tasks.count(Wrappers.<AidMediaTask>lambdaQuery()
                .in(AidMediaTask::getModelName, members.stream().map(AidAiModel::getModelCode).toList())
                .notIn(AidMediaTask::getStatus, List.of("SUCCEEDED", "FAILED", "CANCELLED"))
                .and(q -> q.isNull(AidMediaTask::getProviderRouteSnapshotJson).or().eq(AidMediaTask::getProviderRouteSnapshotJson, "")));
        if (running > 0) fail("请等待原模型任务完成");
        definitions.validate(decision.getCapabilities());
        Map<Long, List<ModelCapabilityDefinition>> originalDefinitions = new LinkedHashMap<>();
        for (AidAiModel member : members) {
            var source = ModelMigrationDefinitions.source(member, definitions.definitions(member.getId()));
            originalDefinitions.put(member.getId(), source);
            for (var capability : source) for (var route : capability.getBindings()) {
                String preserved = ModelMigrationDefinitions.routeCode(member.getId(), route.getCode(), members.size() > 1);
                if (decision.getCapabilities().stream().noneMatch(d -> Objects.equals(d.getCode(), capability.getCode())
                        && d.getBindings().stream().anyMatch(r -> Objects.equals(r.getCode(), preserved)))) fail("原调用配置未保留");
            }
        }
        validateConflictDecisions(decision, members, originalDefinitions);
        // 先更新已有别名的协议命名空间，保存定义时仍由同一事务校验它们存在。
        for (var member : members) for (var alias : definitions.aliasesForModel(member.getId())) {
            alias.setBindingCode(ModelMigrationDefinitions.routeCode(member.getId(), alias.getBindingCode(), members.size() > 1));
            alias.setModelId(canonical.getId()); aliases.updateById(alias);
        }
        for (String field : OPERATIONAL_FIELDS) BeanUtil.setProperty(canonical, field, BeanUtil.getProperty(operation, field));
        canonical.setModelName(decision.getModelName().trim());
        canonical.setCapabilities(decision.getCapabilities());
        if (decision.getBusinessBindings() == null) fail("请确认迁移后的业务绑定");
        // 功能池中的旧 ID 在下面一起替换，再写入能力绑定。
        canonical.setBusinessBindings(null);
        definitions.save(canonical, false);

        List<AidAiModelFuncConfig> pools = functions.list(Wrappers.<AidAiModelFuncConfig>lambdaQuery().eq(AidAiModelFuncConfig::getDelFlag, "0"));
        List<AidAiBusinessModelBinding> nextBindings = decision.getBusinessBindings();
        for (AidAiModelFuncConfig pool : pools) {
            List<Long> ids = pool.getModelIds() == null ? List.of() : JSON.parseArray(pool.getModelIds(), Long.class);
            List<Long> matched = ids.stream().filter(decision.getModelIds()::contains).toList();
            if (matched.isEmpty()) continue;
            if (nextBindings.stream().noneMatch(binding -> Objects.equals(binding.getFuncCode(), pool.getFuncCode()))) fail("原业务绑定未保留");
            List<Long> replaced = ids.stream().map(id -> decision.getModelIds().contains(id) ? canonical.getId() : id).distinct().toList();
            pool.setModelIds(JSON.toJSONString(replaced)); pool.setUpdateBy(actor); pool.setUpdateTime(DateUtils.getNowDate()); functions.updateById(pool);
        }
        businessBindings.replaceForModel(canonical.getId(), nextBindings, actor);
        for (AidAiModel member : members) {
            var original = originalDefinitions.get(member.getId());
            var defaultCapability = original.stream().filter(cap -> Boolean.TRUE.equals(cap.getDefaultCapability())).findFirst().orElseThrow();
            var defaultRoute = defaultCapability.getBindings().stream().filter(route -> Boolean.TRUE.equals(route.getDefaultBinding())).findFirst().orElseThrow();
            AidAiModelAlias alias = definitions.alias(member.getId());
            if (alias == null) alias = new AidAiModelAlias();
            boolean insert = alias.getId() == null;
            alias.setLegacyModelId(member.getId()); alias.setLegacyModelCode(member.getModelCode()); alias.setModelId(canonical.getId());
            if (insert) {
                alias.setCapabilityCode(defaultCapability.getCode());
                alias.setBindingCode(ModelMigrationDefinitions.routeCode(member.getId(), defaultRoute.getCode(), members.size() > 1));
                alias.setCreateBy(actor); alias.setCreateTime(DateUtils.getNowDate()); aliases.insert(alias);
            }
            if (Objects.equals(member.getId(), canonical.getId())) continue;
            references.migrate(member, canonical);
            voices.update(Wrappers.<AidAiVoiceLibrary>lambdaUpdate().eq(AidAiVoiceLibrary::getModelId, member.getId())
                    .set(AidAiVoiceLibrary::getModelId, canonical.getId()).set(AidAiVoiceLibrary::getUpdateBy, actor)
                    .set(AidAiVoiceLibrary::getUpdateTime, DateUtils.getNowDate()));
            models.update(Wrappers.<AidAiModel>lambdaUpdate().eq(AidAiModel::getId, member.getId())
                    .set(AidAiModel::getDelFlag, "1").set(AidAiModel::getStatus, "1")
                    .set(AidAiModel::getUpdateBy, actor).set(AidAiModel::getUpdateTime, DateUtils.getNowDate()));
        }
        return members.size();
    }

    private void validateConflictDecisions(Decision decision, List<AidAiModel> members, Map<Long, List<ModelCapabilityDefinition>> original) {
        Set<String> codes = new LinkedHashSet<>(); original.values().forEach(list -> list.forEach(cap -> codes.add(cap.getCode())));
        for (String code : codes) {
            Set<String> alternatives = new LinkedHashSet<>(); Set<Long> sources = new LinkedHashSet<>();
            original.forEach((id, list) -> list.stream().filter(cap -> code.equals(cap.getCode())).forEach(cap -> {
                alternatives.add(JSON.toJSONString(List.of(cap.getParameters() == null ? List.of() : cap.getParameters(), cap.getRules() == null ? List.of() : cap.getRules()))); sources.add(id);
            }));
            if (alternatives.size() > 1 && (decision.getCapabilitySources() == null || !sources.contains(decision.getCapabilitySources().get(code)))) fail("请明确选择冲突能力的参数来源");
        }
        Map<String, Set<String>> alternatives = new LinkedHashMap<>(); Map<String, Set<Long>> sources = new LinkedHashMap<>();
        for (var member : members) {
            var rows = businessBindings.forModel(member.getId());
            if (rows.isEmpty()) rows = businessBindings.legacyBindings(member);
            for (var row : rows) {
                String key = row.getFuncCode() + "/" + row.getCapabilityCode();
                alternatives.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(row.getDefaultsJson());
                sources.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(member.getId());
            }
        }
        for (String key : alternatives.keySet()) if (alternatives.get(key).size() > 1
                && (decision.getBusinessDefaultSources() == null || !sources.get(key).contains(decision.getBusinessDefaultSources().get(key)))) fail("请明确选择业务默认参数来源");
    }

    private static void fail(String message) { log.info("模型迁移校验失败: {}", message); throw new ServiceException(message); }
}
