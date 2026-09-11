package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelParameter;
import com.alibaba.fastjson2.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 将存量的明确参数选项转换为可视化字段，不推断尚未声明的模型能力。 */
public final class LegacyModelParameterConverter {
    private LegacyModelParameterConverter() { }

    public static List<ModelParameter> convert(AidAiModel model, JSONObject capability) {
        List<ModelParameter> fields = new ArrayList<>();
        List<ModelParameter> options = new ArrayList<>();
        boolean audio = "audio".equals(model.getModelType());
        fields.add(field(audio ? "ttsText" : "prompt", audio ? "合成文本" : "提示词", "string"));
        if ("image".equals(model.getModelType())) {
            addChoice(fields, "size", "输出规格", "string", capability, "sizeOptions", "defaultSize", model.getDefaultSizeCode());
            // 自定义像素尺寸沿用协议的像素边界校验，不能收窄为预置档位枚举。
            if (capability.getBooleanValue("allowCustomWH")) fields.stream().filter(field -> "size".equals(field.getName())).forEach(field -> field.setChoices(null));
            addChoice(options, "aspectRatio", "画面比例", "string", capability, "aspectRatioOptions", "defaultAspectRatio", model.getDefaultAspectRatio());
            if (model.getMaxOutputCount() != null && model.getMaxOutputCount() > 0) {
                ModelParameter count = field("expectedImageCount", "生成张数", "integer");
                count.setMinimum(BigDecimal.ONE); count.setMaximum(BigDecimal.valueOf(model.getMaxOutputCount()));
                count.setDefaultValue(model.getDefaultOutputCount()); fields.add(count);
            }
        } else if ("video".equals(model.getModelType())) {
            addChoice(fields, "durationSeconds", "视频时长", "integer", capability, "durationOptions", "defaultDurationSeconds", model.getDefaultDurationSeconds());
            addChoice(fields, "aspectRatio", "画面比例", "string", capability, "aspectRatioOptions", "defaultAspectRatio", model.getDefaultAspectRatio());
            addChoice(options, "resolution", "输出规格", "string", capability, "sizeOptions", "defaultSize", model.getDefaultSizeCode());
            // 存量调用也可能用 options.size；保留适配器原有默认值解析，避免新增字段抢占显式旧参数。
            options.forEach(parameter -> parameter.setDefaultValue(null));
            for (String toggle : List.of("audio", "bgm")) {
                if (capability.getBooleanValue("audio".equals(toggle) ? "supportsAudio" : "supportsBgm"))
                    fields.add(field(toggle, "audio".equals(toggle) ? "生成音频" : "背景音乐", "boolean"));
            }
        }
        if (!options.isEmpty()) {
            ModelParameter group = field("options", "生成参数", "object");
            group.setProperties(options); group.setDefaultValue(new JSONObject()); fields.add(group);
        }
        return fields;
    }

    private static void addChoice(List<ModelParameter> fields, String name, String label, String type,
            JSONObject capability, String choicesKey, String defaultKey, Object fallbackDefault) {
        Object value = capability.get(choicesKey);
        if (!(value instanceof List<?> choices) || choices.isEmpty()) return;
        // 对象形式的规格需要保留原展示契约，不能把整个对象误作上游参数值。
        if (choices.stream().anyMatch(item -> "integer".equals(type) ? !(item instanceof Number) : !(item instanceof String))) return;
        ModelParameter field = field(name, label, type);
        field.setChoices(new ArrayList<>(choices));
        Object defaultValue = capability.containsKey(defaultKey) ? capability.get(defaultKey) : fallbackDefault;
        if (defaultValue != null) field.setDefaultValue(defaultValue);
        if ("durationSeconds".equals(name)) field.setUnit("秒");
        fields.add(field);
    }

    private static ModelParameter field(String name, String label, String type) {
        ModelParameter field = new ModelParameter();
        field.setName(name); field.setLabel(label); field.setType(type);
        if ("prompt".equals(name) || "ttsText".equals(name)) field.setWidget("textarea");
        return field;
    }
}
