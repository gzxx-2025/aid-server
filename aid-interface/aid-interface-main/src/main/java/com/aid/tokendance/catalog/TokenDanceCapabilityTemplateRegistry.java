package com.aid.tokendance.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 提供 TokenDance 本地目录的官方能力模板和未核验门禁。
 */
public final class TokenDanceCapabilityTemplateRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CATALOG_RESOURCE = "tokendance/catalog/cost-snapshot.json";
    private static final String TEMPLATE_RESOURCE = "tokendance/catalog/verified-capabilities.json";
    private static final String SOURCE_INDEX_RESOURCE = "tokendance/catalog/official-source-index.json";
    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_UNVERIFIED = "UNVERIFIED";
    private static final String COVERAGE_COMPLETE = "COMPLETE";
    private static final List<String> SOURCE_PRIORITY = List.of(
            "LOCAL_VENDOR_OFFICIAL_CAPTURE",
            "TOKENDANCE_AI_INTEGRATION_INDEX",
            "VENDOR_OFFICIAL_ONLINE",
            "VENDOR_FOCUSED_WEB_SEARCH",
            "TOKENDANCE_CHANNEL_FALLBACK");
    private static final Set<String> EVIDENCE_STATUSES = Set.of(
            STATUS_VERIFIED, STATUS_PARTIAL, STATUS_UNVERIFIED);
    private static final Set<String> COVERAGE_STATUSES = Set.of(
            COVERAGE_COMPLETE, STATUS_PARTIAL, STATUS_UNVERIFIED);

    private final Map<String, Set<String>> catalogProtocols;
    private final Map<String, CatalogMetadata> catalogMetadata;
    private final Map<String, ModelTemplate> modelTemplates;
    private final Map<String, SourceProfile> sourceProfiles;
    private final List<String> requiredActivationChecks;
    private final List<String> coverageWarnings;

    public TokenDanceCapabilityTemplateRegistry() {
        this(readResource(CATALOG_RESOURCE), readResource(TEMPLATE_RESOURCE), readResource(SOURCE_INDEX_RESOURCE));
    }

    TokenDanceCapabilityTemplateRegistry(JsonNode catalogRoot, JsonNode templateRoot, JsonNode sourceRoot) {
        requirePositiveInteger(templateRoot, "schemaVersion");
        validateSourcePolicy(sourceRoot);
        if (!"MANUAL_REVIEW_REQUIRED".equals(requireText(templateRoot, "defaultActivationPolicy"))) {
            throw new IllegalStateException("启用策略无效");
        }
        this.catalogProtocols = loadCatalogProtocols(catalogRoot);
        this.catalogMetadata = loadCatalogMetadata(catalogRoot);
        this.requiredActivationChecks = readStringList(
                templateRoot.path("requiredActivationChecks"), "requiredActivationChecks");
        if (requiredActivationChecks.isEmpty()) {
            throw new IllegalStateException("启用检查为空");
        }
        this.modelTemplates = loadModelTemplates(templateRoot, catalogProtocols);
        this.sourceProfiles = loadSourceProfiles(sourceRoot, catalogProtocols);
        this.coverageWarnings = buildCoverageWarnings(catalogProtocols, modelTemplates, sourceProfiles);
    }

    /**
     * 返回指定目录模型和协议的只读能力解析结果。
     *
     * @param modelId 模型标识
     * @param protocol 协议标识
     * @return 能力解析结果
     */
    public Resolution resolve(String modelId, String protocol) {
        Set<String> supportedProtocols = catalogProtocols.get(modelId);
        if (supportedProtocols == null) {
            throw new IllegalArgumentException("模型不在目录");
        }
        if (!supportedProtocols.contains(protocol)) {
            throw new IllegalArgumentException("协议不受支持");
        }

        ModelTemplate modelTemplate = modelTemplates.get(modelId);
        ProtocolTemplate protocolTemplate = modelTemplate == null
                ? null : modelTemplate.protocols().get(protocol);
        SourceProfile sourceProfile = sourceProfiles.get(modelId);
        boolean catalogFallback = modelTemplate != null
                && STATUS_UNVERIFIED.equals(modelTemplate.evidenceStatus())
                && protocolTemplate == null;
        boolean completeOfficialSourceFallback = catalogFallback && sourceProfile != null
                && sourceProfile.unresolved().isEmpty();
        String evidenceStatus = modelTemplate == null ? STATUS_UNVERIFIED : modelTemplate.evidenceStatus();
        String coverageStatus = protocolTemplate == null ? STATUS_UNVERIFIED : protocolTemplate.coverageStatus();
        if (catalogFallback) {
            evidenceStatus = completeOfficialSourceFallback ? STATUS_VERIFIED : STATUS_PARTIAL;
            coverageStatus = completeOfficialSourceFallback ? COVERAGE_COMPLETE : STATUS_PARTIAL;
        }
        String verificationStatus = resolveVerificationStatus(evidenceStatus, coverageStatus);

        LinkedHashSet<String> blockers = new LinkedHashSet<>(requiredActivationChecks);
        List<Evidence> evidence = List.of();
        if (modelTemplate == null) {
            blockers.add("OFFICIAL_CAPABILITY_EVIDENCE_MISSING");
        } else if (catalogFallback) {
            CatalogMetadata metadata = catalogMetadata.get(modelId);
            List<Evidence> resolvedEvidence = new ArrayList<>();
            resolvedEvidence.add(new Evidence("TokenDance AI 接入索引", "https://tokendance.space/docs/ai-integration"));
            if (sourceProfile != null) resolvedEvidence.addAll(sourceProfile.evidence());
            if (metadata != null) resolvedEvidence.add(new Evidence("TokenDance 模型详情与成本快照来源", metadata.sourceUrl()));
            evidence = List.copyOf(resolvedEvidence);
            if (sourceProfile == null) {
                blockers.add("OFFICIAL_CAPABILITY_DETAILS_MISSING");
            } else {
                blockers.addAll(sourceProfile.unresolved());
            }
        } else {
            blockers.addAll(modelTemplate.activationBlockers());
            List<Evidence> resolvedEvidence = new ArrayList<>();
            resolvedEvidence.add(new Evidence("TokenDance AI 接入索引", "https://tokendance.space/docs/ai-integration"));
            if (sourceProfile != null) {
                resolvedEvidence.addAll(sourceProfile.evidence());
                blockers.addAll(sourceProfile.unresolved());
            }
            resolvedEvidence.addAll(modelTemplate.evidence());
            evidence = List.copyOf(resolvedEvidence);
        }
        if (protocolTemplate == null && !catalogFallback) {
            blockers.add("OFFICIAL_PROTOCOL_CAPABILITY_MISSING");
        } else {
            if (protocolTemplate != null) blockers.addAll(protocolTemplate.unresolved());
        }

        ObjectNode capability;
        if (protocolTemplate == null) {
            capability = catalogFallback ? catalogCapability(modelId, protocol) : MAPPER.createObjectNode();
        } else {
            capability = MAPPER.createObjectNode();
            if (sourceProfile != null) {
                overlay(capability, sourceProfile.capability());
                ObjectNode protocolCapability = sourceProfile.protocolCapabilities().get(protocol);
                if (protocolCapability != null) overlay(capability, protocolCapability);
            }
            overlay(capability, protocolTemplate.capability());
        }
        // 先补齐已声明模态的开关，再与协议取交集，避免缺失开关被提前固化为 false。
        completeOptionSwitches(capability);
        applyProtocolRuntimeConstraints(capability, protocol);
        completeOptionSwitches(capability);
        List<JsonNode> requirements = protocolTemplate == null
                ? List.of() : copyNodes(protocolTemplate.requirements());
        return new Resolution(
                modelId,
                protocol,
                evidenceStatus,
                coverageStatus,
                verificationStatus,
                capability,
                requirements,
                evidence,
                List.copyOf(blockers),
                false);
    }

    /**
     * TokenDance 模型目录是官方权威来源。专题文档尚未给出文件/数量等细节时，
     * 仍把目录明确声明的模态、协议和上下文落入模型，避免多模态模型被错误导入成纯文本；
     * 同时保留细节缺失门禁，不能据此自动启用。
     */
    private ObjectNode catalogCapability(String modelId, String protocol) {
        CatalogMetadata metadata = catalogMetadata.get(modelId);
        ObjectNode capability = MAPPER.createObjectNode();
        if (metadata == null) return capability;
        capability.set("inputModalities", MAPPER.valueToTree(metadata.inputModalities().stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT)).toList()));
        capability.set("outputModalities", MAPPER.valueToTree(metadata.outputModalities().stream()
                .map(value -> value.toUpperCase(java.util.Locale.ROOT)).toList()));
        capability.set("allowedInputs", MAPPER.valueToTree(metadata.inputModalities()));
        capability.put("supportsTextInput", metadata.inputModalities().contains("text"));
        capability.put("supportsImageInput", metadata.inputModalities().contains("image"));
        capability.put("supportsVideoInput", metadata.inputModalities().contains("video"));
        capability.put("supportsAudioInput", metadata.inputModalities().contains("audio"));
        capability.put("supportsDocumentInput", metadata.inputModalities().contains("document"));
        if (metadata.contextLength() > 0) capability.put("contextWindowTokens", metadata.contextLength());
        SourceProfile profile = sourceProfiles.get(modelId);
        if (profile != null) {
            overlay(capability, profile.capability());
            ObjectNode protocolCapability = profile.protocolCapabilities().get(protocol);
            if (protocolCapability != null) overlay(capability, protocolCapability);
            capability.put("catalogEvidenceOnly", false);
            capability.put("officialEvidencePartial", !profile.unresolved().isEmpty());
        } else {
            capability.put("catalogEvidenceOnly", true);
        }
        return capability;
    }

    private static void overlay(ObjectNode target, ObjectNode source) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode current = target.get(field.getKey());
            if (current != null && current.isObject() && field.getValue().isObject()) {
                overlay((ObjectNode) current, (ObjectNode) field.getValue());
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }

    /**
     * 能力配置是“模型能力 ∩ 当前协议客户端能力”。原厂模型支持某种模态，并不表示
     * TokenDance 的每一种兼容协议和本地序列化器都能安全传递该模态。
     */
    private static void applyProtocolRuntimeConstraints(ObjectNode capability, String protocol) {
        if (Set.of("openai:chat-completions", "openai:responses", "anthropic:messages").contains(protocol)) {
            // 当前本地目录只包含模型推理成本，不包含上游托管搜索/代码执行等附加计费项。
            capability.put("supportsBuiltinTools", false);
        }
        if (!"openai:responses".equals(protocol) && !"anthropic:messages".equals(protocol)) {
            return;
        }
        boolean anthropic = "anthropic:messages".equals(protocol);
        retainInputModalities(capability, anthropic ? Set.of("text", "image", "video") : Set.of("text", "image"));
        capability.put("supportsVideoInput", anthropic && capability.path("supportsVideoInput").asBoolean(false));
        capability.put("supportsAudioInput", false);
        capability.put("supportsDocumentInput", false);
        // 当前两个适配器尚未把通用 JSON Schema/托管工具配置转换为各自协议字段。
        // 能力声明必须服从真实运行实现，不能让前置校验放行后再由 Provider 拒绝。
        capability.put("supportsStructuredOutput", false);
        capability.put("supportsReasoningBudget",
                anthropic && capability.path("supportsReasoningBudget").asBoolean(false));
        if (!anthropic) capability.put("maxInputVideos", 0);
        capability.put("maxInputAudios", 0);
        capability.put("maxInputDocuments", 0);
        List<String> unsupportedFields = new ArrayList<>(List.of(
                "minInputAudioDurationSeconds", "maxInputAudioDurationSeconds",
                "maxInputAudioTotalDurationSeconds", "maxInputAudioFileSizeMb",
                "maxInputDocumentPages", "maxInputDocumentFileSizeMb"));
        if (!anthropic) {
            unsupportedFields.addAll(List.of(
                    "minInputVideoDurationSeconds", "maxInputVideoDurationSeconds",
                    "maxInputVideoTotalDurationSeconds", "maxInputVideoFileSizeMb", "inputVideoMaxPixels"));
            capability.set("inputVideoFormats", MAPPER.createArrayNode());
        }
        capability.remove(unsupportedFields);
        capability.set("inputAudioFormats", MAPPER.createArrayNode());
        capability.set("inputDocumentFormats", MAPPER.createArrayNode());
    }

    private static void retainInputModalities(ObjectNode capability, Set<String> supported) {
        for (String field : List.of("inputModalities", "allowedInputs")) {
            JsonNode current = capability.get(field);
            if (current == null || !current.isArray()) continue;
            var retained = MAPPER.createArrayNode();
            current.forEach(value -> {
                if (value.isTextual() && supported.contains(value.asText().toLowerCase(java.util.Locale.ROOT))) {
                    retained.add(value.asText());
                }
            });
            capability.set(field, retained);
        }
        if (!capability.path("allowedInputs").isArray() && capability.path("inputModalities").isArray()) {
            var allowed = MAPPER.createArrayNode();
            capability.path("inputModalities").forEach(value -> {
                if (value.isTextual()) allowed.add(value.asText().toLowerCase(java.util.Locale.ROOT));
            });
            capability.set("allowedInputs", allowed);
        }
    }

    /** 只补目录已明确列出的能力的配套开关，不使用后台通用候选项，也不覆盖显式 false。 */
    private static void completeOptionSwitches(ObjectNode capability) {
        JsonNode modalities = capability.path("inputModalities");
        if (modalities.isArray()) {
            Set<String> values = new LinkedHashSet<>();
            modalities.forEach(value -> {
                if (value.isTextual()) values.add(value.asText().toLowerCase(java.util.Locale.ROOT));
            });
            Map.of("text", "supportsTextInput", "image", "supportsImageInput",
                    "video", "supportsVideoInput", "audio", "supportsAudioInput",
                    "document", "supportsDocumentInput").forEach((modality, flag) -> {
                if (!capability.has(flag)) capability.put(flag, values.contains(modality));
            });
            if (!capability.path("allowedInputs").isArray()) {
                capability.set("allowedInputs", MAPPER.valueToTree(values));
            }
        }
        Map.of("sizeOptions", "supportsSizePreset", "aspectRatioOptions", "supportsAspectRatio",
                "durationOptions", "supportsDuration").forEach((options, flag) -> {
            if (!capability.has(flag) && capability.path(options).isArray() && !capability.path(options).isEmpty()) {
                capability.put(flag, true);
            }
        });
        if (!capability.has("supportsMultiImageInput") && capability.path("maxReferenceImages").asInt(0) > 1) {
            capability.put("supportsMultiImageInput", true);
        }
    }

    /**
     * 仅将模板中缺失的字段合并到当前能力配置。
     *
     * @param modelId 模型标识
     * @param protocol 协议标识
     * @param currentCapability 当前能力配置
     * @return 无损合并结果
     */
    public ApplyResult applyMissing(String modelId, String protocol, ObjectNode currentCapability) {
        Resolution resolution = resolve(modelId, protocol);
        ObjectNode merged = currentCapability == null
                ? MAPPER.createObjectNode() : currentCapability.deepCopy();
        List<String> appliedPaths = new ArrayList<>();
        mergeMissing(merged, resolution.capabilityTemplate(), "", appliedPaths);
        return new ApplyResult(resolution, merged, List.copyOf(appliedPaths));
    }

    /**
     * 返回成本目录中的模型数量。
     *
     * @return 模型数量
     */
    public int catalogModelCount() {
        return catalogProtocols.size();
    }

    /**
     * 返回能力资源中显式登记的模型数量。
     *
     * @return 模型数量
     */
    public int explicitModelCount() {
        return modelTemplates.size();
    }

    /**
     * 返回具有官方证据的模型数量。
     *
     * @return 模型数量
     */
    public int verifiedModelCount() {
        return (int) modelTemplates.values().stream()
                .filter(template -> STATUS_VERIFIED.equals(template.evidenceStatus()))
                .count();
    }

    /**
     * 汇总模型在全部目录协议上的验证状态。
     *
     * @param modelId 模型标识
     * @return 验证状态
     */
    public String modelVerificationStatus(String modelId) {
        Set<String> protocols = catalogProtocols.get(modelId);
        if (protocols == null) {
            throw new IllegalArgumentException("模型不在目录");
        }
        boolean allVerified = true;
        boolean allUnverified = true;
        for (String protocol : protocols) {
            String status = resolve(modelId, protocol).verificationStatus();
            allVerified &= STATUS_VERIFIED.equals(status);
            allUnverified &= STATUS_UNVERIFIED.equals(status);
        }
        if (allVerified) {
            return STATUS_VERIFIED;
        }
        return allUnverified ? STATUS_UNVERIFIED : STATUS_PARTIAL;
    }

    /**
     * 判断能力资源与当前成本目录是否完全对齐。
     *
     * @return 是否完全对齐
     */
    public boolean catalogAligned() {
        return coverageWarnings.isEmpty();
    }

    /**
     * 返回能力资源相对当前成本目录的覆盖告警。
     *
     * @return 覆盖告警
     */
    public List<String> coverageWarnings() {
        return coverageWarnings;
    }

    private static JsonNode readResource(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = MAPPER.readTree(inputStream);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("能力资源无效");
            }
            return root;
        } catch (IOException exception) {
            throw new IllegalStateException("能力资源无效", exception);
        }
    }

    private static Map<String, Set<String>> loadCatalogProtocols(JsonNode root) {
        JsonNode models = root.path("models");
        requireArray(models, "catalog.models");
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        for (JsonNode model : models) {
            String modelId = requireText(model, "modelId");
            List<String> protocols = readStringList(model.path("supportedProtocols"),
                    modelId + ".supportedProtocols");
            if (protocols.isEmpty()) {
                throw new IllegalStateException("目录协议为空");
            }
            if (result.putIfAbsent(modelId,
                    Collections.unmodifiableSet(new LinkedHashSet<>(protocols))) != null) {
                throw new IllegalStateException("目录模型重复");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, CatalogMetadata> loadCatalogMetadata(JsonNode root) {
        JsonNode models = root.path("models");
        requireArray(models, "catalog.models");
        LinkedHashMap<String, CatalogMetadata> result = new LinkedHashMap<>();
        for (JsonNode model : models) {
            String modelId = requireText(model, "modelId");
            String sourceUrl = requireText(model, "sourceUrl");
            validateOfficialUrl(sourceUrl);
            List<String> inputs = readStringList(model.path("architecture").path("input_modalities"),
                    modelId + ".input_modalities").stream()
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList();
            List<String> outputs = readStringList(model.path("architecture").path("output_modalities"),
                    modelId + ".output_modalities").stream()
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList();
            long contextLength = model.path("contextLength").asLong(0);
            result.put(modelId, new CatalogMetadata(sourceUrl, inputs, outputs, contextLength));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, ModelTemplate> loadModelTemplates(
            JsonNode root,
            Map<String, Set<String>> catalogProtocols) {
        JsonNode models = root.path("models");
        requireArray(models, "templates.models");
        LinkedHashMap<String, ModelTemplate> result = new LinkedHashMap<>();
        for (JsonNode model : models) {
            String modelId = requireText(model, "modelId");
            String evidenceStatus = requireText(model, "evidenceStatus");
            if (!EVIDENCE_STATUSES.contains(evidenceStatus)) {
                throw new IllegalStateException("证据状态无效");
            }
            List<Evidence> evidence = readEvidence(model.path("evidence"));
            if (!STATUS_UNVERIFIED.equals(evidenceStatus) && evidence.isEmpty()) {
                throw new IllegalStateException("官方证据缺失");
            }
            if (!STATUS_UNVERIFIED.equals(evidenceStatus)) {
                requireText(model, "verifiedAt");
            }
            List<String> activationBlockers = readStringList(
                    model.path("activationBlockers"), modelId + ".activationBlockers");
            Map<String, ProtocolTemplate> protocols = readProtocolTemplates(
                    modelId, model.path("protocols"), catalogProtocols.get(modelId));
            if (STATUS_UNVERIFIED.equals(evidenceStatus) && !protocols.isEmpty()) {
                throw new IllegalStateException("未核验模板非空");
            }
            ModelTemplate template = new ModelTemplate(
                    evidenceStatus,
                    evidence,
                    activationBlockers,
                    protocols);
            if (result.putIfAbsent(modelId, template) != null) {
                throw new IllegalStateException("能力模型重复");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, SourceProfile> loadSourceProfiles(
            JsonNode root,
            Map<String, Set<String>> catalogProtocols) {
        requirePositiveInteger(root, "schemaVersion");
        JsonNode entries = root.path("profiles");
        requireArray(entries, "sourceIndex.profiles");
        LinkedHashMap<String, SourceProfile> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            List<String> modelIds = readStringList(entry.path("modelIds"), "sourceProfile.modelIds");
            List<Evidence> evidence = readEvidence(entry.path("evidence"));
            if (evidence.isEmpty()) throw new IllegalStateException("官方来源索引缺少证据");
            JsonNode capabilityNode = entry.path("capability");
            if (!capabilityNode.isObject()) throw new IllegalStateException("官方来源能力无效");
            JsonNode protocolNode = entry.path("protocolCapabilities");
            if (!protocolNode.isObject()) throw new IllegalStateException("官方来源协议能力无效");
            LinkedHashMap<String, ObjectNode> protocolCapabilities = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> protocolFields = protocolNode.fields();
            while (protocolFields.hasNext()) {
                Map.Entry<String, JsonNode> field = protocolFields.next();
                if (!field.getValue().isObject()) throw new IllegalStateException("官方来源协议能力无效");
                protocolCapabilities.put(field.getKey(), ((ObjectNode) field.getValue()).deepCopy());
            }
            List<String> unresolved = readStringList(entry.path("unresolved"), "sourceProfile.unresolved");
            SourceProfile profile = new SourceProfile(evidence, ((ObjectNode) capabilityNode).deepCopy(),
                    Collections.unmodifiableMap(protocolCapabilities), unresolved);
            for (String modelId : modelIds) {
                Set<String> supported = catalogProtocols.get(modelId);
                if (supported == null) throw new IllegalStateException("官方来源模型不在目录: " + modelId);
                for (String protocol : protocolCapabilities.keySet()) {
                    if (!supported.contains(protocol)) {
                        throw new IllegalStateException("官方来源协议越界: " + modelId + ":" + protocol);
                    }
                }
                if (result.putIfAbsent(modelId, profile) != null) {
                    throw new IllegalStateException("官方来源模型重复: " + modelId);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 固化能力证据优先级，防止后续目录维护时误引入非原厂聚合资料。 */
    private static void validateSourcePolicy(JsonNode root) {
        List<String> priority = readStringList(root.path("sourcePriority"), "sourceIndex.sourcePriority");
        JsonNode policy = root.path("sourcePolicy");
        List<String> excluded = readStringList(
                policy.path("excludedSourceKinds"), "sourceIndex.excludedSourceKinds");
        if (!SOURCE_PRIORITY.equals(priority)
                || !policy.path("combineLocalAndTokenDanceIndex").asBoolean(false)
                || !policy.path("requireOriginalVendorWhenAvailable").asBoolean(false)
                || !policy.path("allowTokenDanceFallbackWhenOriginalMissing").asBoolean(false)
                || !policy.path("originalVendorSourcesOnly").asBoolean(false)
                || !excluded.contains("PRIVATE_PROVIDER")
                || !excluded.contains("UNVERIFIED_THIRD_PARTY_GATEWAY")) {
            throw new IllegalStateException("官方来源策略无效");
        }
    }

    private static Map<String, ProtocolTemplate> readProtocolTemplates(
            String modelId,
            JsonNode protocolsNode,
            Set<String> catalogSupportedProtocols) {
        if (!protocolsNode.isObject()) {
            throw new IllegalStateException("协议模板无效");
        }
        LinkedHashMap<String, ProtocolTemplate> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = protocolsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String protocol = field.getKey();
            if (catalogSupportedProtocols != null && !catalogSupportedProtocols.contains(protocol)) {
                throw new IllegalStateException("模板协议越界");
            }
            JsonNode protocolNode = field.getValue();
            String coverageStatus = requireText(protocolNode, "coverageStatus");
            if (!COVERAGE_STATUSES.contains(coverageStatus)) {
                throw new IllegalStateException("覆盖状态无效");
            }
            JsonNode capabilityNode = protocolNode.path("capability");
            if (!capabilityNode.isObject()) {
                throw new IllegalStateException("能力模板无效");
            }
            List<JsonNode> requirements = readNodeList(
                    protocolNode.path("requirements"), modelId + ".requirements");
            List<String> unresolved = readStringList(
                    protocolNode.path("unresolved"), modelId + ".unresolved");
            result.put(protocol, new ProtocolTemplate(
                    coverageStatus,
                    ((ObjectNode) capabilityNode).deepCopy(),
                    requirements,
                    unresolved));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Evidence> readEvidence(JsonNode evidenceNode) {
        requireArray(evidenceNode, "evidence");
        List<Evidence> result = new ArrayList<>();
        for (JsonNode evidence : evidenceNode) {
            String title = requireText(evidence, "title");
            String url = requireText(evidence, "url");
            validateOfficialUrl(url);
            result.add(new Evidence(title, url));
        }
        return List.copyOf(result);
    }

    private static void validateOfficialUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException("证据地址无效");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("证据地址无效", exception);
        }
    }

    private static List<String> readStringList(JsonNode node, String fieldName) {
        requireArray(node, fieldName);
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("字符串列表无效");
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static List<JsonNode> readNodeList(JsonNode node, String fieldName) {
        requireArray(node, fieldName);
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isObject()) {
                throw new IllegalStateException("条件模板无效");
            }
            result.add(value.deepCopy());
        }
        return List.copyOf(result);
    }

    private static void requireArray(JsonNode node, String fieldName) {
        if (!node.isArray()) {
            throw new IllegalStateException(fieldName + "无效");
        }
    }

    private static String requireText(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(fieldName + "无效");
        }
        return value.textValue();
    }

    private static void requirePositiveInteger(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        if (!value.isIntegralNumber() || value.intValue() <= 0) {
            throw new IllegalStateException(fieldName + "无效");
        }
    }

    private static String resolveVerificationStatus(String evidenceStatus, String coverageStatus) {
        if (STATUS_UNVERIFIED.equals(evidenceStatus) || STATUS_UNVERIFIED.equals(coverageStatus)) {
            return STATUS_UNVERIFIED;
        }
        if (STATUS_PARTIAL.equals(evidenceStatus) || STATUS_PARTIAL.equals(coverageStatus)) {
            return STATUS_PARTIAL;
        }
        return STATUS_VERIFIED;
    }

    private static List<String> buildCoverageWarnings(
            Map<String, Set<String>> catalogProtocols,
            Map<String, ModelTemplate> templates,
            Map<String, SourceProfile> sourceProfiles) {
        List<String> warnings = new ArrayList<>();
        for (String modelId : catalogProtocols.keySet()) {
            ModelTemplate template = templates.get(modelId);
            if (template == null) {
                warnings.add("MISSING_TEMPLATE:" + modelId);
                continue;
            }
            if (STATUS_UNVERIFIED.equals(template.evidenceStatus()) && !sourceProfiles.containsKey(modelId)) {
                warnings.add("MISSING_OFFICIAL_SOURCE_PROFILE:" + modelId);
            }
            if (!STATUS_UNVERIFIED.equals(template.evidenceStatus())) {
                for (String protocol : catalogProtocols.get(modelId)) {
                    if (!template.protocols().containsKey(protocol)) {
                        warnings.add("MISSING_PROTOCOL_TEMPLATE:" + modelId + ":" + protocol);
                    }
                }
            }
        }
        for (String modelId : templates.keySet()) {
            if (!catalogProtocols.containsKey(modelId)) {
                warnings.add("STALE_TEMPLATE:" + modelId);
            }
        }
        return List.copyOf(warnings);
    }

    private static void mergeMissing(
            ObjectNode target,
            ObjectNode template,
            String parentPath,
            List<String> appliedPaths) {
        Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = parentPath + "/" + escapeJsonPointer(field.getKey());
            JsonNode templateValue = field.getValue();
            if (!target.has(field.getKey())) {
                target.set(field.getKey(), templateValue.deepCopy());
                appliedPaths.add(path);
                continue;
            }
            JsonNode currentValue = target.get(field.getKey());
            if (currentValue.isObject() && templateValue.isObject()) {
                mergeMissing((ObjectNode) currentValue, (ObjectNode) templateValue, path, appliedPaths);
            }
        }
    }

    private static String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static List<JsonNode> copyNodes(List<JsonNode> nodes) {
        List<JsonNode> result = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            result.add(node.deepCopy());
        }
        return List.copyOf(result);
    }

    /**
     * 单个模型协议的能力解析结果。
     */
    public record Resolution(
            String modelId,
            String protocol,
            String evidenceStatus,
            String coverageStatus,
            String verificationStatus,
            ObjectNode capabilityTemplate,
            List<JsonNode> requirements,
            List<Evidence> evidence,
            List<String> activationBlockers,
            boolean autoActivationAllowed) {
    }

    /**
     * 能力模板的无损合并结果。
     */
    public record ApplyResult(
            Resolution resolution,
            ObjectNode capability,
            List<String> appliedPaths) {
    }

    /**
     * 官方证据链接。
     */
    public record Evidence(String title, String url) {
    }

    private record ModelTemplate(
            String evidenceStatus,
            List<Evidence> evidence,
            List<String> activationBlockers,
            Map<String, ProtocolTemplate> protocols) {
    }

    private record ProtocolTemplate(
            String coverageStatus,
            ObjectNode capability,
            List<JsonNode> requirements,
            List<String> unresolved) {
    }

    private record CatalogMetadata(
            String sourceUrl,
            List<String> inputModalities,
            List<String> outputModalities,
            long contextLength) {
    }

    private record SourceProfile(
            List<Evidence> evidence,
            ObjectNode capability,
            Map<String, ObjectNode> protocolCapabilities,
            List<String> unresolved) {
    }
}
