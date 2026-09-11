package com.aid.tokendance.catalog;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.billing.model.BillingRule;
import com.aid.common.exception.ServiceException;
import com.aid.upgrade.util.VersionCompareUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在线目录差异预览、兼容性判断和幂等草稿导入。 */
@Service
@Slf4j
public class TokenDanceCatalogServiceImpl implements ITokenDanceCatalogService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IAidAiModelService models;
    private final IAidAiProviderService providers;
    private final TokenDanceCatalogManager catalogManager;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelDefinitionService modelDefinitions;
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("openai:chat-completions", "/gateway/v1/chat/completions"),
            Map.entry("openai:responses", "/gateway/v1/responses"),
            Map.entry("anthropic:messages", "/gateway/v1/messages"),
            Map.entry("ark:image-generations", "/gateway/ark/v3/images/generations"),
            Map.entry("openai:image-generations", "/gateway/v1/images/generations"),
            Map.entry("seedance:generations", "/gateway/ark/v3/generations/tasks"),
            Map.entry("kling:text2video", "/gateway/kling/v1/text2video"),
            Map.entry("kling:image2video", "/gateway/kling/v1/image2video"),
            Map.entry("kling:omni-video", "/gateway/kling/v1/omni-video"),
            Map.entry("wan3:video-synthesis", "/gateway/alibaba/wan3/v1/video-synthesis"),
            Map.entry("happyhorse:video-synthesis", "/gateway/alibaba/happyhorse/v1/video-synthesis"),
            Map.entry("minimax:video_generation_v2", "/gateway/minimax/v2/video_generation"),
            Map.entry("minimax:t2a_v2", "/gateway/minimax/v1/t2a_v2"),
            Map.entry("minimax:t2a_v2_ws", "/gateway/minimax/v1/t2a_v2_ws"),
            Map.entry("minimax:voice_clone", "/gateway/minimax/v1/voice_clone"),
            Map.entry("ark:tts", "/gateway/ark/v3/tts/unidirectional"),
            Map.entry("ark:tts_ws", "/gateway/ark/v3/tts/bidirection"));

    private static final Set<String> SUPPORTED_FEATURES = supportedFeatures();

    public TokenDanceCatalogServiceImpl(
            IAidAiModelService models,
            IAidAiProviderService providers,
            TokenDanceCatalogManager catalogManager) {
        this.models = models;
        this.providers = providers;
        this.catalogManager = catalogManager;
    }

    @Override
    public List<JsonNode> list(String keyword, Long providerId) {
        TokenDanceCatalogSnapshot snapshot = catalogManager.current(true);
        if (providerId != null) requireProvider(providerId);
        Map<String, List<AidAiModel>> imported = providerId == null ? Map.of() : models.list(
                        Wrappers.<AidAiModel>lambdaQuery()
                                .eq(AidAiModel::getProviderId, providerId)
                                .eq(AidAiModel::getDelFlag, "0"))
                .stream().collect(java.util.stream.Collectors.groupingBy(model -> StrUtil.blankToDefault(model.getRealModelCode(), model.getModelCode())));
        String term = StrUtil.blankToDefault(keyword, "").trim().toLowerCase(Locale.ROOT);
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode entry : snapshot.entries().values()) {
            if (!term.isEmpty() && !(entry.path("modelId").asText() + " " + entry.path("name").asText())
                    .toLowerCase(Locale.ROOT).contains(term)) continue;
            ObjectNode summary = ((ObjectNode) entry).deepCopy();
            summary.remove("items");
            summary.put("costItemCount", entry.path("items").size());
            summary.put("priceType", "model_call_cost");
            summary.put("currency", "CNY");
            String modelId = entry.path("modelId").asText();
            summary.put("verificationStatus", snapshot.capabilities().modelVerificationStatus(modelId));
            summary.put("catalogVersion", snapshot.catalogVersion());
            summary.put("catalogSource", snapshot.source());
            ObjectNode pricingByProtocol = MAPPER.createObjectNode();
            ObjectNode compatibilityByProtocol = MAPPER.createObjectNode();
            boolean allPricingComplete = true;
            for (String protocol : values(entry.path("supportedProtocols"))) {
                boolean complete = compile(snapshot, entry, protocol).complete();
                pricingByProtocol.put(protocol, complete);
                compatibilityByProtocol.set(protocol,
                        compatibility(snapshot, entry, protocol, providerId, complete, imported));
                allPricingComplete &= complete;
            }
            summary.set("pricingCompleteByProtocol", pricingByProtocol);
            summary.set("compatibilityByProtocol", compatibilityByProtocol);
            summary.put("pricingComplete", allPricingComplete);
            result.add(summary);
        }
        return result;
    }

    @Override
    public JsonNode detail(String modelId) {
        TokenDanceCatalogSnapshot snapshot = catalogManager.current(true);
        ObjectNode result = ((ObjectNode) requireEntry(snapshot, modelId)).deepCopy();
        ObjectNode protocols = MAPPER.createObjectNode();
        for (String protocol : values(result.path("supportedProtocols"))) {
            boolean pricingComplete = compile(snapshot, result, protocol).complete();
            protocols.set(protocol, MAPPER.valueToTree(pricingAwareEvidence(
                    snapshot.capabilities().resolve(modelId, protocol), pricingComplete)));
        }
        result.set("capabilityEvidence", protocols);
        ObjectNode compatibilityByProtocol = MAPPER.createObjectNode();
        for (String protocol : values(result.path("supportedProtocols"))) {
            compatibilityByProtocol.set(protocol, compatibility(snapshot, result, protocol, null, null, null));
        }
        result.set("compatibilityByProtocol", compatibilityByProtocol);
        result.put("catalogVersion", snapshot.catalogVersion());
        result.put("catalogSource", snapshot.source());
        return result;
    }

    @Override
    public JsonNode preview(Long providerId, String modelId, String protocol) {
        requireProvider(providerId);
        TokenDanceCatalogSnapshot snapshot = catalogManager.current(true);
        JsonNode entry = requireSelection(snapshot, modelId, protocol);
        ObjectNode compatibility = compatibility(snapshot, entry, protocol, providerId, null, null);
        AidAiModel candidate = buildModel(snapshot, providerId, entry, protocol);
        AidAiModel existing = existing(candidate);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("modelId", modelId);
        result.put("protocol", protocol);
        result.put("catalogVersion", snapshot.catalogVersion());
        result.set("compatibility", compatibility);
        result.put("localModelCode", candidate.getModelCode());
        result.set("capability", parse(candidate.getCapabilityJson()));
        result.set("billingRule", parse(candidate.getBillingRuleJson()));
        var pricing = compile(snapshot, entry, protocol);
        result.put("pricingComplete", pricing.complete());
        result.set("warnings", MAPPER.valueToTree(pricing.warnings()));
        var evidence = snapshot.capabilities().resolve(modelId, protocol);
        result.put("verificationStatus", evidence.verificationStatus());
        result.set("capabilityEvidence", MAPPER.valueToTree(pricingAwareEvidence(evidence, pricing.complete())));
        result.put("importAction", existing == null ? "CREATE_DISABLED" : "PRESERVE_EXISTING");
        if (existing != null) {
            result.put("existingModelId", existing.getId());
            if (existing.getConfigVersion() != null) result.put("configVersion", existing.getConfigVersion());
            var selected = protocolViews(existing, protocol);
            result.put("importAction", selected.isEmpty() ? "ADD_PROTOCOL" : "PRESERVE_EXISTING");
            result.put("capabilityChanged", selected.stream().anyMatch(view -> !catalogComparableCapability(view.getCapabilityJson())
                    .equals(catalogComparableCapability(candidate.getCapabilityJson()))));
            result.put("billingChanged", selected.stream().anyMatch(view -> !parse(view.getBillingRuleJson()).equals(parse(candidate.getBillingRuleJson()))));
            result.put("canRepairEmptyPricing",
                    compatibility.path("importable").asBoolean(false)
                            && pricing.complete() && selected.stream().anyMatch(view -> hasRepairableCatalogPricing(view, pricing.rule())));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void repairEmptyPricing(Long providerId, String modelId, String protocol, Long configVersion) {
        requireProvider(providerId);
        TokenDanceCatalogSnapshot snapshot = catalogManager.current(true);
        if (TokenDanceCompatibilityPolicy.blockedReason(snapshot.bundle(), modelId) != null) {
            log.info("TokenDance 不兼容模型拒绝目录修复: modelId={}", modelId);
            throw new ServiceException("不兼容AID禁止导入");
        }
        JsonNode entry = requireSelection(snapshot, modelId, protocol);
        AidAiModel candidate = buildModel(snapshot, providerId, entry, protocol);
        AidAiModel current = existing(candidate);
        if (current == null || !providerId.equals(current.getProviderId()) || !"0".equals(current.getDelFlag())) {
            log.info("TokenDance SKU 修复未找到模型: providerId={}, modelId={}, protocol={}",
                    providerId, modelId, protocol);
            throw new ServiceException("模型不存在或已删除");
        }
        if (configVersion == null || !configVersion.equals(current.getConfigVersion())) {
            log.info("TokenDance SKU 修复版本冲突: modelCode={}, expected={}, actual={}",
                    current.getModelCode(), configVersion, current.getConfigVersion());
            throw new ServiceException("配置已更新请刷新");
        }
        var configured = modelDefinitions.definitions(current.getId());
        if (!configured.isEmpty()) {
            var pricing = compile(snapshot, entry, protocol);
            if (!pricing.complete()) throw new ServiceException("目录成本尚不完整");
            var selectedRoutes = configured.stream().flatMap(cap -> cap.getBindings().stream())
                    .filter(route -> ("tokendance:" + protocol).equals(route.getProtocol())).toList();
            if (selectedRoutes.isEmpty()) throw new ServiceException("模型尚未绑定此协议");
            boolean repaired = false;
            for (var route : selectedRoutes) {
                AidAiModel priced = new AidAiModel();
                priced.setCapabilityJson(com.alibaba.fastjson2.JSON.toJSONString(route.getCapability()));
                priced.setBillingMode(route.getBillingMode());
                priced.setBillingRuleJson(com.alibaba.fastjson2.JSON.toJSONString(route.getBillingRule()));
                if (!hasRepairableCatalogPricing(priced, pricing.rule())) continue;
                route.setBillingRule(com.alibaba.fastjson2.JSON.parseObject(MAPPER.valueToTree(pricing.rule()).toString()));
                repaired = true;
            }
            if (!repaired) throw new ServiceException("已有价格不能覆盖");
            current.setCapabilities(configured);
            modelDefinitions.save(current, false);
            return;
        }
        JsonNode currentCapability = parse(current.getCapabilityJson());
        if (!modelId.equals(currentCapability.path("catalogModelId").asText())
                || !protocol.equals(currentCapability.path("catalogProtocol").asText())) {
            log.info("TokenDance SKU 修复目录绑定不匹配: modelCode={}, modelId={}, protocol={}",
                    current.getModelCode(), modelId, protocol);
            throw new ServiceException("目录绑定不匹配");
        }
        var pricing = compile(snapshot, entry, protocol);
        if (!hasRepairableCatalogPricing(current, pricing.rule())) {
            log.info("TokenDance SKU 修复拒绝覆盖已有价格: modelCode={}", current.getModelCode());
            throw new ServiceException("已有价格不能覆盖");
        }
        if (!pricing.complete()) {
            log.info("TokenDance SKU 修复成本不完整: modelId={}, protocol={}, warnings={}",
                    modelId, protocol, pricing.warnings());
            throw new ServiceException("目录成本不完整");
        }
        // 只提交计费字段；服务端现有合并器保留未知扩展字段，CAS 防止覆盖并发编辑。
        AidAiModel update = new AidAiModel();
        update.setId(current.getId());
        update.setModelCode(current.getModelCode());
        update.setConfigVersion(configVersion);
        update.setBillingMode("SKU");
        ObjectNode patch = MAPPER.valueToTree(pricing.rule());
        // 序列化的 null 并不表示管理员要求删除配置；不向合并器提交这些占位字段。
        List<String> absent = new ArrayList<>();
        patch.fields().forEachRemaining(field -> { if (field.getValue().isNull()) absent.add(field.getKey()); });
        patch.remove(absent);
        update.setBillingRuleJson(patch.toString());
        models.updateAidAiModel(update);
    }

    private boolean hasRepairableCatalogPricing(AidAiModel model, BillingRule catalogRule) {
        JsonNode billing = parse(model.getBillingRuleJson());
        JsonNode capability = parse(model.getCapabilityJson());
        JsonNode skus = billing.path("skus");
        boolean emptyOrDisabled = skus.isMissingNode() || skus.isNull()
                || (skus.isArray() && (skus.isEmpty()
                || java.util.stream.StreamSupport.stream(skus.spliterator(), false)
                .noneMatch(sku -> sku.path("enabled").asBoolean(false))));
        return "SKU".equals(model.getBillingMode()) && capability.hasNonNull("catalogModelId")
                && capability.hasNonNull("catalogProtocol") && billing.isObject()
                && (emptyOrDisabled || isLegacyUnnumberedCatalogPricing(billing, catalogRule));
    }

    /**
     * 兼容早期目录编译器生成的 priority=0：仅当整份规则除优先级外与当前目录结果完全一致时允许修复，
     * 防止“修复目录 SKU”覆盖管理员已经调整过的价格、条件或扩展字段。
     */
    private boolean isLegacyUnnumberedCatalogPricing(JsonNode current, BillingRule catalogRule) {
        if (catalogRule == null || !current.isObject()) return false;
        JsonNode currentSkus = current.path("skus");
        JsonNode expected = MAPPER.valueToTree(catalogRule);
        JsonNode expectedSkus = expected.path("skus");
        if (!currentSkus.isArray() || currentSkus.isEmpty()
                || !expectedSkus.isArray() || currentSkus.size() != expectedSkus.size()) {
            return false;
        }
        for (JsonNode sku : currentSkus) {
            if (!sku.path("priority").isIntegralNumber() || sku.path("priority").intValue() != 0) {
                return false;
            }
        }
        ObjectNode normalizedCurrent = ((ObjectNode) current).deepCopy();
        ObjectNode normalizedExpected = ((ObjectNode) expected).deepCopy();
        normalizeSkuPriorities(normalizedCurrent);
        normalizeSkuPriorities(normalizedExpected);
        return normalizedCurrent.equals(normalizedExpected);
    }

    private void normalizeSkuPriorities(ObjectNode rule) {
        JsonNode skus = rule.path("skus");
        if (!skus.isArray()) return;
        for (JsonNode sku : skus) {
            if (sku instanceof ObjectNode object) object.put("priority", 0);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ImportResult> importModels(Long providerId, String catalogVersion, List<Selection> selections) {
        requireProvider(providerId);
        TokenDanceCatalogSnapshot snapshot = catalogManager.current(true);
        if (StrUtil.isNotBlank(catalogVersion) && !Objects.equals(catalogVersion, snapshot.catalogVersion())) {
            throw new ServiceException("目录已更新请刷新");
        }
        if (selections == null || selections.isEmpty() || selections.size() > 200) throw new ServiceException("请选择有效模型");
        // 同一供应商串行化导入，模型唯一索引仍提供跨实例最终保护。
        providers.getOne(Wrappers.<AidAiProvider>lambdaQuery().eq(AidAiProvider::getId, providerId)
                .last("FOR UPDATE"), false);
        List<ImportResult> results = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection == null) throw new ServiceException("模型选择无效");
            JsonNode entry = requireSelection(snapshot, selection.modelId(), selection.protocol());
            ObjectNode compatible = compatibility(snapshot, entry, selection.protocol(), providerId, null, null);
            if (!compatible.path("importable").asBoolean(false)) {
                log.info("TokenDance 目录导入被兼容门禁拒绝: modelId={}, protocol={}, status={}",
                        selection.modelId(), selection.protocol(), compatible.path("status").asText());
                throw new ServiceException("模型暂不可导入");
            }
            AidAiModel candidate = buildModel(snapshot, providerId, entry, selection.protocol());
            AidAiModel saved = existing(candidate);
            if (saved == null) {
                addProtocolDefinitions(candidate, null);
                saved = candidate;
                results.add(new ImportResult(selection.modelId(), selection.protocol(), saved.getId(), "CREATED_DISABLED"));
            } else {
                if (!providerId.equals(saved.getProviderId())) throw new ServiceException("模型编码已被占用");
                boolean added = addProtocolDefinitions(candidate, saved);
                results.add(new ImportResult(selection.modelId(), selection.protocol(), saved.getId(), added ? "PROTOCOL_ADDED" : "UNCHANGED"));
            }
        }
        return results;
    }

    private AidAiModel buildModel(
            TokenDanceCatalogSnapshot snapshot, Long providerId, JsonNode entry, String protocol) {
        String id = entry.path("modelId").asText();
        AidAiModel model = new AidAiModel();
        model.setProviderId(providerId);
        model.setModelCode("td_" + providerId + "_" + (id.length() > 60 ? SecureUtil.sha256(id) : id));
        model.setRealModelCode(id);
        model.setModelName(entry.path("name").asText());
        model.setProtocol("tokendance:" + protocol);
        model.setApiSuffix(PATHS.get(protocol));
        List<String> outputs = values(entry.path("architecture").path("output_modalities"));
        String type = outputs.contains("video") ? "video" : outputs.contains("image") ? "image" : outputs.contains("audio") ? "audio" : "text";
        if (protocol.contains("tts") || protocol.contains("t2a") || protocol.contains("voice_clone")) type = "audio";
        model.setModelType(type);
        model.setGenerateMode(defaultGenerateMode(type, id, protocol));
        model.setStatus("1");
        model.setDelFlag("0");
        model.setPriority(0);
        model.setBillingMode("SKU");
        model.setBillingVersion(1);
        model.setBillingMultiplier(BigDecimal.ONE);
        model.setIsFree(false);
        List<String> inputs = values(entry.path("architecture").path("input_modalities"));
        model.setSupportsTextInput(inputs.contains("text"));
        model.setSupportsImageInput(inputs.contains("image"));
        var evidence = snapshot.capabilities().resolve(id, protocol);
        ObjectNode capability = evidence.capabilityTemplate().deepCopy();
        capability.put("requiresConfiguredBilling", true);
        if (!capability.has("supportsVideoInput")) capability.put("supportsVideoInput", inputs.contains("video"));
        if (!capability.has("supportsAudioInput")) capability.put("supportsAudioInput", inputs.contains("audio"));
        if (!capability.has("allowedInputs")) capability.set("allowedInputs", MAPPER.valueToTree(inputs));
        if (!capability.has("inputModalities")) capability.set("inputModalities", MAPPER.valueToTree(inputs.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList()));
        if (!capability.has("outputModalities")) capability.set("outputModalities", MAPPER.valueToTree(outputs.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList()));
        capability.put("catalogModelId", id);
        capability.put("catalogProtocol", protocol);
        capability.put("catalogSource", entry.path("sourceUrl").asText());
        capability.put("catalogFetchedAt", entry.path("fetchedAt").asText());
        capability.put("catalogVersion", snapshot.catalogVersion());
        capability.put("verificationStatus", evidence.verificationStatus());
        if (!capability.has("contextWindowTokens") && entry.path("contextLength").asLong() > 0) capability.put("contextWindowTokens", entry.path("contextLength").asLong());
        if (capability.has("supportsTextInput")) model.setSupportsTextInput(capability.path("supportsTextInput").asBoolean());
        if (capability.has("supportsImageInput")) model.setSupportsImageInput(capability.path("supportsImageInput").asBoolean());
        if (capability.has("supportsAspectRatio")) model.setSupportsAspectRatio(capability.path("supportsAspectRatio").asBoolean());
        if (capability.has("supportsSizePreset")) model.setSupportsSizePreset(capability.path("supportsSizePreset").asBoolean());
        if (capability.has("supportsDuration")) model.setSupportsDuration(capability.path("supportsDuration").asBoolean());
        if (capability.has("supportsFirstFrame")) model.setSupportsFirstFrame(capability.path("supportsFirstFrame").asBoolean());
        if (capability.has("supportsLastFrame")) model.setSupportsLastFrame(capability.path("supportsLastFrame").asBoolean());
        if (capability.has("supportsMultiImageInput")) model.setSupportsMultiImageInput(capability.path("supportsMultiImageInput").asBoolean());
        if (capability.path("maxOutputCount").isIntegralNumber()) model.setMaxOutputCount(capability.path("maxOutputCount").intValue());
        if (capability.hasNonNull("defaultSize")) model.setDefaultSizeCode(capability.path("defaultSize").asText());
        else if (capability.hasNonNull("defaultSizeCode")) model.setDefaultSizeCode(capability.path("defaultSizeCode").asText());
        if (capability.hasNonNull("defaultAspectRatio")) model.setDefaultAspectRatio(capability.path("defaultAspectRatio").asText());
        if (capability.path("defaultDurationSeconds").isIntegralNumber()) model.setDefaultDurationSeconds(capability.path("defaultDurationSeconds").intValue());
        applySceneSummary(model, capability);
        model.setCapabilityJson(capability.toString());
        TokenDanceCatalogPricingCompiler.Compilation pricing = compile(snapshot, entry, protocol);
        // 未完整编译的成本仅供目录查看，绝不能留下部分可计费 SKU 让管理员误启用。
        if (!pricing.complete()) pricing.rule().getSkus().forEach(sku -> sku.setEnabled(false));
        model.setBillingRuleJson(MAPPER.valueToTree(pricing.rule()).toString());
        model.setRemark("TokenDance 在线目录导入；请完成凭证与真实协议联调后启用。");
        return model;
    }

    private static String defaultGenerateMode(String type, String modelId, String protocol) {
        if ("image".equals(type)) return "text_to_image";
        if (!"video".equals(type)) return type;
        if (modelId.endsWith("-r2v")) return "reference_to_video";
        if (protocol.contains("image2video") || modelId.endsWith("-i2v")) return "image_to_video";
        return "text_to_video";
    }

    /** 将多场景目录能力投影到旧列表字段；真实调用仍以选中的场景定义为准。 */
    private static void applySceneSummary(AidAiModel model, ObjectNode capability) {
        JsonNode scenes = capability.path("sceneRules");
        if (!scenes.isObject()) return;
        boolean first = scenes.has("imageToVideo") || scenes.has("startEndToVideo");
        boolean last = scenes.has("startEndToVideo");
        if (!capability.has("supportsFirstFrame")) model.setSupportsFirstFrame(first);
        if (!capability.has("supportsLastFrame")) model.setSupportsLastFrame(last);
    }

    private AidAiModel existing(String modelCode) {
        return models.getOne(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getModelCode, modelCode).last("limit 1"), false);
    }

    private AidAiModel existing(AidAiModel candidate) {
        List<AidAiModel> matches = models.list(Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getProviderId, candidate.getProviderId())
                .eq(AidAiModel::getRealModelCode, candidate.getRealModelCode())
                .eq(AidAiModel::getDelFlag, "0"));
        if (matches.size() > 1) {
            log.info("目录模型有待合并记录: providerId={}, modelId={}", candidate.getProviderId(), candidate.getRealModelCode());
            throw new ServiceException("请先统一重复模型");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean addProtocolDefinitions(AidAiModel candidate, AidAiModel saved) {
        var incoming = com.aid.model.definition.LegacyModelDefinitionConverter.convert(candidate);
        for (var definition : incoming) for (var route : definition.getBindings()) {
            route.setCode("protocol_" + SecureUtil.sha256(route.getProtocol()).substring(0, 16));
        }
        if (saved == null) {
            candidate.setCapabilities(incoming);
            modelDefinitions.save(candidate, true);
            return true;
        }
        List<com.aid.aid.domain.model.ModelCapabilityDefinition> configured = new ArrayList<>(modelDefinitions.definitions(saved.getId()));
        if (configured.isEmpty()) configured.addAll(com.aid.model.definition.LegacyModelDefinitionConverter.convert(saved));
        boolean changed = false;
        for (var definition : incoming) {
            var current = configured.stream().filter(d -> Objects.equals(d.getCode(), definition.getCode())).findFirst().orElse(null);
            if (current == null) {
                definition.setDefaultCapability(false);
                definition.setEnabled(false);
                configured.add(definition); changed = true;
            } else {
                var routes = new ArrayList<>(current.getBindings());
                for (var route : definition.getBindings()) {
                    if (routes.stream().anyMatch(r -> Objects.equals(r.getProtocol(), route.getProtocol()))) continue;
                    route.setDefaultBinding(false); route.setEnabled(false); routes.add(route); changed = true;
                }
                current.setBindings(routes);
            }
        }
        if (changed) { saved.setCapabilities(configured); modelDefinitions.save(saved, false); }
        return changed;
    }

    /** 已完成成本编译时移除价格缺失阻断；协议联调和凭证检查仍在真正启用时执行。 */
    private TokenDanceCapabilityTemplateRegistry.Resolution pricingAwareEvidence(
            TokenDanceCapabilityTemplateRegistry.Resolution evidence, boolean pricingComplete) {
        if (!pricingComplete || !evidence.activationBlockers().contains("PRICING_COVERAGE_REQUIRED")) {
            return evidence;
        }
        List<String> blockers = evidence.activationBlockers().stream()
                .filter(blocker -> !"PRICING_COVERAGE_REQUIRED".equals(blocker))
                .toList();
        return new TokenDanceCapabilityTemplateRegistry.Resolution(
                evidence.modelId(),
                evidence.protocol(),
                evidence.evidenceStatus(),
                evidence.coverageStatus(),
                evidence.verificationStatus(),
                evidence.capabilityTemplate(),
                evidence.requirements(),
                evidence.evidence(),
                blockers,
                evidence.autoActivationAllowed());
    }

    private void requireProvider(Long providerId) {
        AidAiProvider provider = providerId == null ? null : providers.getById(providerId);
        if (provider == null || !"tokendance".equalsIgnoreCase(provider.getProviderCode()) || !"0".equals(provider.getDelFlag())) {
            throw new ServiceException("供应商不匹配");
        }
    }

    private JsonNode requireSelection(TokenDanceCatalogSnapshot snapshot, String id, String protocol) {
        JsonNode entry = requireEntry(snapshot, id);
        if (!PATHS.containsKey(protocol == null ? "" : protocol) || !values(entry.path("supportedProtocols")).contains(protocol)) {
            throw new ServiceException("模型协议不支持");
        }
        return entry;
    }

    private JsonNode requireEntry(TokenDanceCatalogSnapshot snapshot, String id) {
        JsonNode entry = snapshot.entries().get(id);
        if (entry == null) throw new ServiceException("模型不在当前目录");
        return entry;
    }

    private TokenDanceCatalogPricingCompiler.Compilation compile(
            TokenDanceCatalogSnapshot snapshot, JsonNode entry, String protocol) {
        return TokenDanceCatalogPricingCompiler.compile(entry, protocol, snapshot.pricingContext());
    }

    private ObjectNode compatibility(
            TokenDanceCatalogSnapshot snapshot,
            JsonNode entry,
            String protocol,
            Long providerId,
            Boolean knownPricingComplete,
            Map<String, List<AidAiModel>> imported) {
        String modelId = entry.path("modelId").asText();
        String status = "IMPORTABLE";
        String reason = "可导入为停用模型";
        List<String> missingFeatures = snapshot.requiredFeatures(modelId, protocol).stream()
                .filter(feature -> !SUPPORTED_FEATURES.contains(feature))
                .toList();
        boolean pricingComplete = knownPricingComplete != null
                ? knownPricingComplete : compile(snapshot, entry, protocol).complete();
        String minimumVersion = snapshot.minimumAppVersion(modelId, protocol);
        String blockedReason = TokenDanceCompatibilityPolicy.blockedReason(snapshot.bundle(), modelId);
        if (blockedReason != null) {
            status = "AID_INCOMPATIBLE";
            reason = blockedReason;
        } else if (snapshot.deprecated(modelId, protocol)) {
            status = "DEPRECATED";
            reason = "目录已标记停用";
        } else if (!VersionCompareUtil.isAtLeast(catalogManager.currentVersion(), minimumVersion)) {
            status = "UPGRADE_REQUIRED";
            reason = "需要升级到 " + minimumVersion;
        } else if (!PATHS.containsKey(protocol)) {
            status = "PROTOCOL_UNSUPPORTED";
            reason = "当前程序未实现该协议";
        } else if (snapshot.capabilityContractVersion(modelId, protocol)
                > catalogManager.supportedCapabilityContract() || !missingFeatures.isEmpty()) {
            status = "CAPABILITY_UNSUPPORTED";
            reason = "当前程序不支持这些能力";
        } else if (snapshot.billingContractVersion(modelId, protocol)
                > catalogManager.supportedBillingContract() || !pricingComplete) {
            status = "PRICING_INCOMPLETE";
            reason = "成本 SKU 尚未完整适配";
        } else if ("UNVERIFIED".equals(snapshot.capabilities().resolve(modelId, protocol).verificationStatus())) {
            status = "CATALOG_UNVERIFIED";
            reason = "目录能力证据不足";
        }

        boolean importable = "IMPORTABLE".equals(status);
        boolean selectable = importable;
        if (importable && providerId != null) {
            AidAiModel candidate = buildModel(snapshot, providerId, entry, protocol);
            List<AidAiModel> matches = imported == null ? models.list(Wrappers.<AidAiModel>lambdaQuery()
                    .eq(AidAiModel::getProviderId, providerId).eq(AidAiModel::getRealModelCode, candidate.getRealModelCode())
                    .eq(AidAiModel::getDelFlag, "0")) : imported.getOrDefault(candidate.getRealModelCode(), List.of());
            var views = matches.size() == 1 ? protocolViews(matches.get(0), protocol) : List.<AidAiModel>of();
            if (matches.size() > 1) {
                status = "MIGRATION_REQUIRED";
                reason = "存在重复模型，请先预检并统一模型";
                importable = false; selectable = false;
            }
            if (!views.isEmpty()) {
                boolean changed = views.stream().anyMatch(current -> !catalogComparableCapability(current.getCapabilityJson())
                        .equals(catalogComparableCapability(candidate.getCapabilityJson()))
                        || !parse(current.getBillingRuleJson()).equals(parse(candidate.getBillingRuleJson())));
                status = changed ? "UPDATE_AVAILABLE" : "IMPORTED";
                reason = changed ? "目录存在更新，现有配置不会自动覆盖" : "已经导入";
                selectable = false;
            }
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("status", status);
        result.put("importable", importable);
        result.put("selectable", selectable);
        result.put("reason", reason);
        result.put("minimumAppVersion", minimumVersion);
        result.put("currentAppVersion", catalogManager.currentVersion());
        result.put("capabilityContractVersion", snapshot.capabilityContractVersion(modelId, protocol));
        result.put("billingContractVersion", snapshot.billingContractVersion(modelId, protocol));
        result.set("requiredFeatures", MAPPER.valueToTree(snapshot.requiredFeatures(modelId, protocol)));
        result.set("missingFeatures", MAPPER.valueToTree(missingFeatures));
        return result;
    }

    /** 目录比较与价格修复只读取所选协议，不能使用模型默认协议的价格。 */
    private List<AidAiModel> protocolViews(AidAiModel model, String protocol) {
        var configured = modelDefinitions.definitions(model.getId());
        if (configured.isEmpty()) return ("tokendance:" + protocol).equals(model.getProtocol()) ? List.of(model) : List.of();
        var routes = configured.stream().flatMap(cap -> cap.getBindings().stream())
                .filter(route -> ("tokendance:" + protocol).equals(route.getProtocol())).toList();
        // 同协议的不同能力可能采用不同 SKU，目录对比必须报告任何一项差异。
        return routes.stream().map(route -> {
            AidAiModel view = new AidAiModel();
            cn.hutool.core.bean.BeanUtil.copyProperties(model, view);
            view.setCapabilityJson(com.alibaba.fastjson2.JSON.toJSONString(route.getCapability()));
            view.setBillingMode(route.getBillingMode());
            view.setBillingRuleJson(com.alibaba.fastjson2.JSON.toJSONString(route.getBillingRule()));
            return view;
        }).toList();
    }

    @Override
    public JsonNode status() {
        return catalogManager.status();
    }

    @Override
    public JsonNode refresh() {
        catalogManager.refresh(true);
        return catalogManager.status();
    }

    private static Set<String> supportedFeatures() {
        LinkedHashSet<String> features = new LinkedHashSet<>();
        features.add("tokendance.catalog.v1");
        features.add("capability.contract.v1");
        features.add("billing.contract.v1");
        PATHS.keySet().forEach(protocol -> features.add("protocol:" + protocol));
        return Set.copyOf(features);
    }

    private static List<String> values(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static JsonNode parse(String json) {
        if (StrUtil.isBlank(json)) return MAPPER.createObjectNode();
        try { return MAPPER.readTree(json); }
        catch (Exception ex) { throw new ServiceException("模型配置无效"); }
    }

    private static JsonNode catalogComparableCapability(String json) {
        JsonNode parsed = parse(json);
        if (parsed instanceof ObjectNode object) {
            ObjectNode normalized = object.deepCopy();
            normalized.remove(List.of("catalogVersion", "catalogFetchedAt", "catalogSource"));
            return normalized;
        }
        return parsed;
    }
}
