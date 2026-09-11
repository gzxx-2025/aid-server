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
            "reasoning_content", "reasoningContent", "thoughts", "thinking_content", "thinkingBlocks", "responseItems");

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
            String type = objectNode.path("type").asText();
            if ("thinking".equals(type) || "redacted_thinking".equals(type) || "reasoning".equals(type)) {
                objectNode.remove(List.of("thinking", "signature", "data", "summary", "content", "encrypted_content"));
                objectNode.put("thoughtOmitted", true);
            }
            if ("thinking_delta".equals(type) || "signature_delta".equals(type)
                    || type.startsWith("response.reasoning")) {
                objectNode.remove(List.of("thinking", "signature", "delta", "text"));
                objectNode.put("thoughtOmitted", true);
            }
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
