package com.aid.storyboard.support;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 高级视频字段的业务边界与最小快照；不保存 URL 或任意 Provider JSON。 */
public final class StoryboardVideoAdvancedOptions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK = "omni_reference_task_type";
    private static final String FORMAT = "output_format";
    private StoryboardVideoAdvancedOptions() { }

    public static void normalize(StoryboardVideoGenerateRequest request, boolean single) {
        String task = trim(request.getOmniReferenceTaskType());
        String format = trim(request.getOutputFormat());
        if (!single && (task != null || format != null || Integer.valueOf(-1).equals(request.getDurationSeconds())))
            throw new ServiceException("高级视频参数仅限单镜");
        if (task != null && !Set.of("reference", "edit", "extend", "auto").contains(task))
            throw new ServiceException("视频任务类型不支持");
        request.setOmniReferenceTaskType(task);
        request.setOutputFormat(format);
        if ("edit".equals(task) || "extend".equals(task)) {
            if (request.getReferenceVideoRecordIds() == null || request.getReferenceVideoRecordIds().isEmpty())
                throw new ServiceException("请选择参考视频");
            if (trim(request.getAspectRatio()) == null) request.setAspectRatio("adaptive");
            if ("edit".equals(task) && request.getDurationSeconds() == null) request.setDurationSeconds(-1);
        }
    }

    public static Map<String, Object> merge(Map<String, Object> planned,
            StoryboardVideoGenerateRequest request, AiModelConfigVo config) {
        Map<String, Object> result = new LinkedHashMap<>(planned == null ? Map.of() : planned);
        String task = trim(request.getOmniReferenceTaskType());
        String format = trim(request.getOutputFormat());
        if (task == null && format == null) return result;
        if (config == null || !"tokendance:seedance:generations".equals(config.getProtocol()))
            throw new ServiceException("高级视频参数尚未适配");
        JsonNode capability;
        try { capability = JSON.readTree(config.getCapabilityJson()); }
        catch (Exception ex) { throw new ServiceException("模型能力配置错误"); }
        if (capability == null) throw new ServiceException("模型能力配置错误");
        requireOption(capability, "seedanceTaskTypeOptions", task);
        requireOption(capability, "outputFormatOptions", format);
        if (task != null) result.put(TASK, task);
        if (format != null) result.put(FORMAT, format);
        return result;
    }

    public static Map<String, Object> snapshot(Map<String, Object> options) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (options != null) for (String key : new String[]{TASK, FORMAT}) {
            if (options.get(key) instanceof String value && !value.isBlank()) result.put(key, value);
        }
        return result;
    }

    public static Map<String, Object> read(JsonNode shot) {
        JsonNode node = shot.path("videoOptions");
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) throw new ServiceException("视频任务快照无效");
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!Set.of(TASK, FORMAT).contains(entry.getKey()) || !entry.getValue().isTextual())
                throw new ServiceException("视频任务快照无效");
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return result;
    }

    public static void restore(StoryboardVideoGenerateRequest request, Map<String, Object> options) {
        if (options == null) return;
        request.setOmniReferenceTaskType((String) options.get(TASK));
        request.setOutputFormat((String) options.get(FORMAT));
    }

    private static void requireOption(JsonNode capability, String key, String value) {
        if (value == null) return;
        JsonNode options = capability.path(key);
        if (options.isArray()) for (JsonNode option : options) {
            if (option.isTextual() && value.equals(option.textValue())) return;
        }
        throw new ServiceException("视频参数不在模型能力内");
    }
    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
