package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;

public final class ProviderResponseHelper {

    // 统一 JSON 解析器：用于 provider 响应快速读取。
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderResponseHelper() {
    }

    public static JsonNode readTree(String raw) {
        // 空字符串直接返回 null，避免抛无意义异常。
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            // 解析原始 JSON 字符串。
            return MAPPER.readTree(raw);
        } catch (Exception ignored) {
            // 解析失败返回 null，交给上游做兜底策略。
            return null;
        }
    }

    public static String readText(JsonNode root, String... paths) {
        // 根节点或路径为空时直接返回 null。
        if (root == null || paths == null) {
            return null;
        }
        // 按候选路径顺序查找第一个非空文本值。
        for (String path : paths) {
            JsonNode hit = nodeByPath(root, path);
            if (hit != null && !hit.isNull()) {
                String value = hit.asText();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    public static JsonNode nodeByPath(JsonNode root, String dotPath) {
        // 参数保护：空 root 或空路径直接返回 null。
        if (root == null || StringUtils.isBlank(dotPath)) {
            return null;
        }
        // 用点分隔路径，支持如 output.results.0.url 的深层读取。
        String[] parts = dotPath.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            // 当前节点为数组且路径段为数字时，按索引取值。
            if (isNumber(part) && current.isArray()) {
                int index = Integer.parseInt(part);
                if (index >= current.size()) {
                    return null;
                }
                current = current.get(index);
                continue;
            }
            // 常规对象字段读取。
            current = current.get(part);
        }
        return current;
    }

    /**
     * 按候选路径顺序读取第一个非空数值（整数），读不到返回 null。
     * 支持整数和浮点数（浮点会向上取整）。
     */
    public static Integer readInt(JsonNode root, String... paths) {
        if (root == null || paths == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode hit = nodeByPath(root, path);
            if (hit != null && !hit.isNull() && hit.isNumber()) {
                // 浮点数向上取整（如 5.04 → 6），整数直接返回
                if (hit.isFloatingPointNumber()) {
                    return (int) Math.ceil(hit.doubleValue());
                }
                return hit.intValue();
            }
        }
        return null;
    }

    public static String findFirstUrl(JsonNode root) {
        // 根节点为空直接返回。
        if (root == null) {
            return null;
        }
        // 纯文本且以 http 开头时，直接视为 URL。
        if (root.isTextual() && root.asText().startsWith("http")) {
            return root.asText();
        }
        if (root.isObject()) {
            // 优先读取标准字段 url。
            JsonNode direct = root.get("url");
            if (direct != null && direct.isTextual() && direct.asText().startsWith("http")) {
                return direct.asText();
            }
            // 对象子节点递归扫描 URL。
            Iterator<JsonNode> elements = root.elements();
            while (elements.hasNext()) {
                String candidate = findFirstUrl(elements.next());
                if (candidate != null) {
                    return candidate;
                }
            }
        } else if (root.isArray()) {
            // 数组节点逐项递归扫描 URL。
            for (JsonNode node : root) {
                String candidate = findFirstUrl(node);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isNumber(String value) {
        // 空值不是数字。
        if (StringUtils.isBlank(value)) {
            return false;
        }
        // 逐字符判断是否全部为数字。
        for (char c : value.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }
}
