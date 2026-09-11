package com.aid.model.definition;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将已校验的能力映射应用到协议请求体，禁止在报价后改写适配器已确定的参数。 */
public final class ModelConfiguredRequestBody {
    private ModelConfiguredRequestBody() { }

    public static String configuredRequestKey(AiModelConfigVo config, Object request) {
        if (config == null || config.getCapabilityCode() == null) return null;
        Object key = apply(config, Map.of(), request == null ? Map.of() : request).get("req_key");
        if (key == null) return null;
        if (!(key instanceof String text) || text.isBlank()) throw new ServiceException("req_key 配置无效");
        return (String) key;
    }

    public static Map<String, Object> apply(AiModelConfigVo config, Map<String, Object> body, Object request) {
        if (config == null || config.getCapabilityCode() == null) return body;
        Map<String, Object> result = new LinkedHashMap<>(body);
        merge(result, JSON.parseObject(config.getModelExtraBodyJson()), "");
        if (config.getRequestMappings() != null && !config.getRequestMappings().isEmpty()) {
            // 素材签名在提交前更新，必须读取这次实际请求，不能复用报价时的临时 URL。
            Map<String, Object> parameters = JSON.parseObject(JSON.toJSONString(request));
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (var mapping : config.getRequestMappings()) {
                Object value = ModelParameterValidator.read(parameters, mapping.getSource());
                if (value != null) ModelParameterValidator.write(mapped, mapping.getTarget(), convert(value, mapping.getType(), mapping.getSource()));
            }
            merge(result, mapped, "");
        }
        return result;
    }

    private static Object convert(Object value, String type, String field) {
        if (type == null || "preserve".equals(type)) return value;
        try {
            return switch (type) {
                case "string" -> {
                    if (value instanceof Map<?, ?> || value instanceof List<?>) throw new IllegalArgumentException();
                    yield value.toString();
                }
                case "number" -> new BigDecimal(value.toString());
                case "integer" -> new BigDecimal(value.toString()).toBigIntegerExact();
                case "boolean" -> {
                    if (value instanceof Boolean) yield value;
                    if ("true".equals(value) || "false".equals(value)) yield Boolean.valueOf(value.toString());
                    throw new IllegalArgumentException();
                }
                case "object" -> { if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(); yield value; }
                case "array" -> { if (!(value instanceof List<?>)) throw new IllegalArgumentException(); yield value; }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException ex) { throw new ServiceException("参数无法转换为目标类型：" + field); }
    }

    /** SDK 请求先使用其原有序列化器，保留协议字段名及空值策略。 */
    public static String applyJson(AiModelConfigVo config, String json, Object request) {
        if (config == null || config.getCapabilityCode() == null) return json;
        return JSON.toJSONString(apply(config, JSON.parseObject(json), request));
    }

    private static void merge(Map<String, Object> target, Map<String, Object> additions, String prefix) {
        if (additions == null) return;
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            String key = entry.getKey();
            Object before = target.get(key);
            Object after = entry.getValue();
            if (before instanceof Map<?, ?> beforeMap && after instanceof Map<?, ?> afterMap) {
                Map<String, Object> nested = new LinkedHashMap<>();
                beforeMap.forEach((k, v) -> nested.put(String.valueOf(k), v));
                Map<String, Object> extension = new LinkedHashMap<>();
                afterMap.forEach((k, v) -> extension.put(String.valueOf(k), v));
                merge(nested, extension, prefix + key + ".");
                target.put(key, nested);
            } else if (before != null && !equal(before, after)) {
                throw new ServiceException("调用配置与业务参数冲突：" + prefix + key);
            } else target.put(key, after);
        }
    }

    private static boolean equal(Object a, Object b) {
        return a instanceof Number && b instanceof Number
                ? new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0 : Objects.equals(a, b);
    }
}
