package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 清除供应商响应中不可落库的明文思考内容。 */
public final class ReasoningContentSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> REASONING_FIELDS = List.of(
            "reasoning_content", "reasoningContent", "thoughts", "thinking_content");

    private ReasoningContentSanitizer() {
    }

    public static String sanitizeJson(String value) {
        if (value == null || value.isBlank() || "[DONE]".equals(value)) {
            return value;
        }
        try {
            JsonNode root = MAPPER.readTree(value);
            sanitize(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ignored) {
            return "[reasoning response omitted]";
        }
    }

    private static void sanitize(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            REASONING_FIELDS.forEach(objectNode::remove);
            if (objectNode.path("thought").asBoolean(false)) {
                objectNode.remove("text");
                objectNode.put("thoughtOmitted", true);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            List<JsonNode> children = new ArrayList<>();
            fields.forEachRemaining(entry -> children.add(entry.getValue()));
            children.forEach(ReasoningContentSanitizer::sanitize);
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(ReasoningContentSanitizer::sanitize);
        }
    }
}
