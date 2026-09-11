package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将当前能力的标准参数投影为既有公共参数组件使用的契约。 */
public final class ModelSchemaPresentation {
    private ModelSchemaPresentation() { }

    public static ModelCapabilityDefinition withBusinessDefaults(ModelCapabilityDefinition definition, String defaultsJson) {
        if (definition == null || defaultsJson == null || defaultsJson.isBlank()) return definition;
        Map<String, Object> defaults = JSON.parseObject(defaultsJson);
        ModelParameterValidator.validateBusinessDefaults(definition, defaults);
        ModelCapabilityDefinition view = JSON.parseObject(JSON.toJSONString(definition), ModelCapabilityDefinition.class);
        overlayDefaults(view.getParameters(), defaults);
        return view;
    }

    private static void overlayDefaults(List<ModelParameter> fields, Map<?, ?> values) {
        if (fields == null) return;
        for (ModelParameter field : fields) {
            Object value = values.get(field.getName());
            if (value == null) continue;
            if ("object".equals(field.getType()) && value instanceof Map<?, ?> nested) {
                Map<String, Object> combined = field.getDefaultValue() instanceof Map<?, ?> existing
                        ? JSON.parseObject(JSON.toJSONString(existing)) : new LinkedHashMap<>();
                merge(combined, nested);
                field.setDefaultValue(combined);
                overlayDefaults(field.getProperties(), nested);
            } else field.setDefaultValue(value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<?, ?> source) {
        source.forEach((key, value) -> {
            Object current = target.get(String.valueOf(key));
            if (current instanceof Map<?, ?> nested && value instanceof Map<?, ?> supplied)
                merge((Map<String, Object>) nested, supplied);
            else target.put(String.valueOf(key), value);
        });
    }

    public static void apply(Object target, ModelCapabilityDefinition definition) {
        if (definition.getParameters() == null || definition.getParameters().isEmpty()) return;
        String json = BeanUtil.getProperty(target, "capabilityJson");
        Map<String, Object> capability = json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.parseObject(json);
        Map<String, ModelParameter> fields = new LinkedHashMap<>();
        collect(definition.getParameters(), "", fields);
        project(target, capability, first(fields, "aspectRatio", "options.aspectRatio"), "aspectRatioOptions", "defaultAspectRatio", "supportsAspectRatio");
        project(target, capability, first(fields, "size", "options.size", "options.resolution"), "sizeOptions", "defaultSizeCode", "supportsSizePreset");
        project(target, capability, fields.get("durationSeconds"), "durationOptions", "defaultDurationSeconds", "supportsDuration");
        ModelParameter count = fields.get("expectedImageCount");
        if (count != null) {
            if (count.getMaximum() != null) BeanUtil.setProperty(target, "maxOutputCount", count.getMaximum().intValue());
            if (count.getDefaultValue() != null) BeanUtil.setProperty(target, "defaultOutputCount", count.getDefaultValue());
        }
        if (fields.containsKey("audio")) capability.put("supportsAudio", true);
        for (var field : fields.values()) {
            if ("first_frame".equals(field.getMaterialRole())) BeanUtil.setProperty(target, "supportsFirstFrame", true);
            if ("last_frame".equals(field.getMaterialRole())) BeanUtil.setProperty(target, "supportsLastFrame", true);
            if ("reference_image".equals(field.getMaterialRole())) {
                BeanUtil.setProperty(target, "supportsImageInput", true);
                if (field.getMaximum() != null) capability.put("maxReferenceImages", field.getMaximum().intValue());
                if (field.getMinimum() != null) capability.put("minReferenceImages", field.getMinimum().intValue());
            }
        }
        BeanUtil.setProperty(target, "capabilityJson", JSON.toJSONString(capability));
    }

    private static void project(Object target, Map<String, Object> capability, ModelParameter field, String optionsKey, String defaultKey, String supportedKey) {
        if (field == null) return;
        BeanUtil.setProperty(target, supportedKey, true);
        List<Object> choices = field.getChoices();
        if ((choices == null || choices.isEmpty()) && "integer".equals(field.getType()) && field.getMinimum() != null && field.getMaximum() != null) {
            BigDecimal step = field.getStep() == null ? BigDecimal.ONE : field.getStep();
            if (field.getMaximum().subtract(field.getMinimum()).divideToIntegralValue(step).compareTo(BigDecimal.valueOf(99)) <= 0) {
                choices = new ArrayList<>();
                for (BigDecimal n = field.getMinimum(); n.compareTo(field.getMaximum()) <= 0; n = n.add(step)) choices.add(n.intValueExact());
            }
        }
        if (choices != null && !choices.isEmpty()) capability.put(optionsKey, choices);
        if (field.getDefaultValue() != null) {
            BeanUtil.setProperty(target, defaultKey, field.getDefaultValue());
            capability.put("defaultSizeCode".equals(defaultKey) ? "defaultSize" : defaultKey, field.getDefaultValue());
        }
    }

    private static ModelParameter first(Map<String, ModelParameter> fields, String... keys) {
        for (String key : keys) if (fields.containsKey(key)) return fields.get(key);
        return null;
    }
    private static void collect(List<ModelParameter> fields, String prefix, Map<String, ModelParameter> result) {
        for (var field : fields) {
            result.put(prefix + field.getName(), field);
            if (field.getProperties() != null) collect(field.getProperties(), prefix + field.getName() + ".", result);
        }
    }
}
