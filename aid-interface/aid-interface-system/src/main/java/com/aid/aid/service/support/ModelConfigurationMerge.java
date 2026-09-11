package com.aid.aid.service.support;

import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

/** 合并模型配置的显式修改，保留未编辑的扩展字段。 */
@Slf4j
public final class ModelConfigurationMerge {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_JSON_LENGTH = 1_000_000;

    private ModelConfigurationMerge() { }

    public static String merge(String previous, String update) {
        if (update == null) return previous;
        ObjectNode incoming = parse(update);
        ObjectNode baseline = previous == null || previous.isBlank() ? MAPPER.createObjectNode() : parse(previous);
        return mergeObject(baseline, incoming).toString();
    }

    public static void validate(String json) {
        if (json != null) parse(json);
    }

    private static ObjectNode parse(String json) {
        try {
            if (json.length() > MAX_JSON_LENGTH) throw new IllegalArgumentException("size");
            JsonNode root = MAPPER.readTree(json);
            if (!(root instanceof ObjectNode object)) throw new IllegalArgumentException("object required");
            return object;
        } catch (Exception ex) {
            log.info("模型配置JSON无效: length={}", json.length());
            throw new ServiceException("模型配置格式无效");
        }
    }

    private static ObjectNode mergeObject(ObjectNode before, ObjectNode update) {
        ObjectNode result = before.deepCopy();
        update.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            JsonNode old = result.get(key);
            if (value.isNull()) {
                result.remove(key);
            } else if (value.isObject() && old != null && old.isObject()) {
                result.set(key, mergeObject((ObjectNode) old, (ObjectNode) value));
            } else if ("skus".equals(key) && value.isArray()) {
                result.set(key, mergeSkus(old, (ArrayNode) value));
            } else {
                // 数组代表本次显式选择的完整集合。
                result.set(key, value.deepCopy());
            }
        });
        return result;
    }

    private static ArrayNode mergeSkus(JsonNode previous, ArrayNode update) {
        ArrayNode result = MAPPER.createArrayNode();
        Set<String> codes = new HashSet<>();
        for (JsonNode item : update) {
            String code = item.path("skuCode").asText("").trim();
            if (!item.isObject() || code.isBlank() || !codes.add(code)) {
                log.info("SKU身份缺失或重复");
                throw new ServiceException("SKU编码无效");
            }
            ObjectNode baseline = MAPPER.createObjectNode();
            if (previous != null && previous.isArray()) {
                for (JsonNode old : previous) {
                    if (old.isObject() && code.equals(old.path("skuCode").asText().trim())) {
                        baseline = (ObjectNode) old;
                        break;
                    }
                }
            }
            result.add(mergeObject(baseline, (ObjectNode) item));
        }
        return result;
    }
}
