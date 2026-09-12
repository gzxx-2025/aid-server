package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.alibaba.fastjson2.JSON;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 为报价和实际调用解析同一份模型能力配置。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelInvocationResolver {
    public static final Set<String> PRESENTATION_FIELDS = Set.of("supportsTextInput", "supportsSystemPrompt", "supportsImageInput",
            "supportsMultiImageInput", "maxOutputCount", "defaultOutputCount", "supportsAspectRatio", "supportsSizePreset",
            "supportsDuration", "supportsFirstFrame", "supportsLastFrame", "defaultSizeCode", "defaultAspectRatio",
            "defaultDurationSeconds", "imageRefine");
    private final ModelDefinitionService definitions;
    private final ModelBusinessBindingService businessBindings;

    public AiModelConfigVo select(AiModelConfigVo config, String capabilityCode) {
        return select(config, capabilityCode, null);
    }

    public AiModelConfigVo select(AiModelConfigVo config, String capabilityCode, String bindingCode) {
        if (config == null) return null;
        List<ModelCapabilityDefinition> all = config.getResolvedCapabilities();
        if (all == null) {
            all = definitions.definitions(config.getId());
            if (all.isEmpty()) {
                AidAiModel legacy = new AidAiModel();
                BeanUtil.copyProperties(config, legacy);
                all = LegacyModelDefinitionConverter.convert(legacy);
            }
            config.setResolvedCapabilities(all);
            Map<String, Object> baseline = new LinkedHashMap<>();
            for (String field : PRESENTATION_FIELDS) baseline.put(field, BeanUtil.getProperty(config, field));
            for (String field : List.of("realModelCode", "taskQuerySuffix", "billingMode", "billingRuleJson", "costCredits"))
                baseline.put(field, BeanUtil.getProperty(config, field));
            config.setInvocationBaseline(baseline);
        }
        String requested = nonblank(capabilityCode) ? capabilityCode : config.getCapabilityCode();
        // 能力编码优先；同一生成模式可以包含对话、FIM 等多个独立能力。
        boolean exactCapability = nonblank(requested) && all.stream()
                .anyMatch(d -> Objects.equals(d.getCode(), requested));
        List<ModelCapabilityDefinition> candidates = all.stream().filter(d -> Boolean.TRUE.equals(d.getEnabled()))
                .filter(d -> nonblank(requested) ? Objects.equals(d.getCode(), requested) || (!exactCapability && Objects.equals(d.getGenerateMode(), requested))
                        : Boolean.TRUE.equals(d.getDefaultCapability())).toList();
        if (candidates.size() != 1) fail("模型能力不可用");
        ModelCapabilityDefinition definition = candidates.get(0);
        config.setResolvedDefinition(definition);
        String requestedBinding = nonblank(bindingCode) ? bindingCode
                : Objects.equals(config.getCapabilityCode(), definition.getCode()) ? config.getBindingCode() : null;
        List<ModelProtocolBinding> routes = definition.getBindings().stream().filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                .filter(r -> nonblank(requestedBinding) ? Objects.equals(r.getCode(), requestedBinding) : Boolean.TRUE.equals(r.getDefaultBinding())).toList();
        if (routes.size() != 1) fail("模型协议不可用");
        ModelProtocolBinding route = routes.get(0);
        if (config.getInvocationBaseline() != null) config.getInvocationBaseline().forEach((field, value) -> BeanUtil.setProperty(config, field, value));
        config.setCapabilityCode(definition.getCode());
        config.setBindingCode(route.getCode());
        config.setRequestMappings(route.getMappings());
        config.setGenerateMode(definition.getGenerateMode());
        config.setProtocol(route.getProtocol());
        if (nonblank(route.getUpstreamModel())) config.setRealModelCode(route.getUpstreamModel());
        config.setApiSuffix(route.getApiSuffix());
        config.setApiVersion(route.getApiVersion());
        if (nonblank(route.getTaskQuerySuffix())) config.setTaskQuerySuffix(route.getTaskQuerySuffix());
        config.setCapabilityJson(JSON.toJSONString(route.getCapability() == null ? Map.of() : route.getCapability()));
        config.setParamMappingJson(JSON.toJSONString(route.getParameterMapping() == null ? Map.of() : route.getParameterMapping()));
        config.setModelExtraBodyJson(JSON.toJSONString(route.getFixedParameters() == null ? Map.of() : route.getFixedParameters()));
        if (route.getBillingMode() != null) config.setBillingMode(route.getBillingMode());
        if (route.getBillingRule() != null) config.setBillingRuleJson(JSON.toJSONString(route.getBillingRule()));
        if (route.getCostCredits() != null) config.setCostCredits(route.getCostCredits());
        applyPresentation(config, definition.getPresentation());
        applyPresentation(config, route.getPresentation());
        ModelSchemaPresentation.apply(config, definition);
        return config;
    }

    public void normalize(AiModelConfigVo config, Object request) {
        normalize(config, request, false);
    }

    /** 前置文本任务尚未产出提示词时，只校验当前已知参数。 */
    public void normalizePlannedPrompt(AiModelConfigVo config, Object request) {
        normalize(config, request, true);
    }

    private void normalize(AiModelConfigVo config, Object request, boolean promptPending) {
        if (config == null) return;
        String businessCode = BeanUtil.getProperty(request, "businessFuncCode");
        if (nonblank(businessCode)) {
            config.setBusinessDefaultsJson(null);
            String requested = BeanUtil.getProperty(request, "capabilityCode");
            String selected = businessBindings.capability(config.getId(), businessCode, requested);
            // 旧标识保留原协议用于兼容；明确的业务调用必须使用该能力当前配置的默认协议。
            config.setBindingCode(null);
            select(config, selected);
            businessBindings.forFunction(businessCode).stream()
                    .filter(b -> Objects.equals(b.getModelId(), config.getId()) && Objects.equals(b.getCapabilityCode(), config.getCapabilityCode()))
                    .findFirst().ifPresent(b -> config.setBusinessDefaultsJson(b.getDefaultsJson()));
        }
        if (config == null || !nonblank(config.getCapabilityCode())) return;
        ModelCapabilityDefinition definition = config.getResolvedDefinition();
        if (definition == null) fail("模型能力不可用");
        Map<String, Object> parameters = new LinkedHashMap<>(JSON.parseObject(JSON.toJSONString(request)));
        ModelRequestParameters.normalizeAliases(config.getModelType(), definition, parameters);
        if (config.getBusinessDefaultsJson() != null && !config.getBusinessDefaultsJson().isBlank()) {
            applyBusinessDefaults(parameters, JSON.parseObject(config.getBusinessDefaultsJson()));
        }
        ModelParameterValidator.normalize(promptPending ? ModelParameterValidator.withDeferredPrompt(definition) : definition, parameters, true);
        // 只回写能力声明的参数，不允许配置修改用户身份或任务归属。
        for (var parameter : definition.getParameters() == null ? List.<ModelParameter>of() : definition.getParameters()) {
            if (parameters.containsKey(parameter.getName())) writeParameter(request, parameter.getName(), parameters.get(parameter.getName()));
        }
        BeanUtil.setProperty(request, "capabilityCode", config.getCapabilityCode());
        BeanUtil.setProperty(request, "modelName", config.getModelCode());
        ModelConfiguredRequestBody.apply(config, Map.of(), request);
        BeanUtil.setProperty(request, "invocationIdentity", SecureUtil.sha256(config.getId() + "/" + config.getCapabilityCode()
                + "/" + config.getBindingCode() + "/" + config.getConfigVersion() + "/" + config.getBusinessDefaultsJson()));
    }

    public void validateVerifiedMaterials(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        if (config == null || !ModelMaterialStatistics.required(config.getResolvedDefinition())) return;
        Map<String, Object> parameters = new LinkedHashMap<>(JSON.parseObject(JSON.toJSONString(request)));
        parameters.put("materials", ModelMaterialStatistics.fromVerifiedVideo(request));
        ModelParameterValidator.normalize(config.getResolvedDefinition(), parameters);
        for (var field : config.getResolvedDefinition().getParameters() == null ? List.<ModelParameter>of() : config.getResolvedDefinition().getParameters()) {
            if (parameters.containsKey(field.getName())) writeParameter(request, field.getName(), parameters.get(field.getName()));
        }
    }

    public static void applyPresentation(Object target, Map<String, Object> presentation) {
        if (presentation == null) return;
        for (String field : PRESENTATION_FIELDS) if (presentation.containsKey(field)) BeanUtil.setProperty(target, field, presentation.get(field));
    }

    private static void writeParameter(Object request, String name, Object value) {
        // BeanUtil 的路径赋值会直接保留 List<JSONObject>，不能恢复消息等嵌套 DTO 的泛型。
        var field = cn.hutool.core.util.ReflectUtil.getField(request.getClass(), name);
        if (field != null && (value instanceof Map<?, ?> || value instanceof List<?>)) {
            value = JSON.parseObject(JSON.toJSONString(value), field.getGenericType());
        }
        BeanUtil.setProperty(request, name, value);
    }

    @SuppressWarnings("unchecked")
    private static void applyBusinessDefaults(Map<String, Object> parameters, Map<String, Object> defaults) {
        defaults.forEach((key, value) -> {
            Object current = parameters.get(key);
            if (current == null) parameters.put(key, value);
            else if (current instanceof Map<?, ?> currentMap && value instanceof Map<?, ?> defaultMap)
                applyBusinessDefaults((Map<String, Object>) currentMap, (Map<String, Object>) defaultMap);
        });
    }

    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
    private static void fail(String message) { log.info("模型调用解析失败: {}", message); throw new ServiceException(message); }
}
