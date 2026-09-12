package com.aid.tokendance.provider.text;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.aid.media.dto.MediaTextGenerateRequest.TextThinkingBlock;
import com.aid.media.dto.MediaTextGenerateRequest.TextToolCall;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 客户端函数的消息关联和三种文本协议映射；不执行模型返回的函数。 */
public final class TokenDanceToolMessages {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TURN_CHARS = 100_000;
    public static final int DEEPSEEK_TURN_CHARS = 4_000_000;
    private TokenDanceToolMessages() { }

    public static boolean requested(MediaTextGenerateRequest request) {
        if (request == null) return false;
        Map<String, Object> options = request.getOptions();
        if (options != null && (options.get("tools") != null
                && (!(options.get("tools") instanceof java.util.Collection<?> values) || !values.isEmpty())
                || options.get("tool_choice") != null && !"none".equals(options.get("tool_choice")))) return true;
        return request.getMessages() != null && request.getMessages().stream().anyMatch(item -> item != null
                && (hasCalls(item) || "tool".equalsIgnoreCase(string(item.getRole()).trim()) || item.getToolCallId() != null
                    || item.getReasoningContent() != null && !Boolean.TRUE.equals(item.getPrefix())
                    || item.getThinkingBlocks() != null || item.getResponseItems() != null));
    }

    public static boolean isToolTurn(AiModelConfigVo config, MediaTextGenerateRequest request) {
        return config != null && config.getProtocol() != null
                && (config.getProtocol().startsWith("tokendance:") || "deepseek:chat-completions".equals(config.getProtocol()))
                && requested(request);
    }

    public static boolean hasCalls(TextMessageItem message) {
        return message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty();
    }

    public static void validate(AiModelConfigVo config, MediaTextGenerateRequest request) {
        validate(config, request, MAX_TURN_CHARS);
    }

    private static void validate(AiModelConfigVo config, MediaTextGenerateRequest request, int reasoningLimit) {
        if (!requested(request)) return;
        if ("deepseek:chat-completions".equals(config.getProtocol())) {
            AiModelConfigVo compatible = new AiModelConfigVo();
            compatible.setCapabilityJson(config.getCapabilityJson());
            compatible.setProtocol(TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS);
            validate(compatible, request, DEEPSEEK_TURN_CHARS);
            return;
        }
        if (!tree(config.getCapabilityJson()).path("supportsToolCalling").asBoolean(false))
            throw new ServiceException("模型不支持工具调用");
        if (!Set.of(TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS, TokenDanceProtocols.OPENAI_RESPONSES,
                TokenDanceProtocols.ANTHROPIC_MESSAGES).contains(config.getProtocol()))
            throw new ServiceException("工具协议尚未适配");
        Object rawTools = request.getOptions() == null ? null : request.getOptions().get("tools");
        if (rawTools != null) {
            if (!(rawTools instanceof java.util.Collection<?> definitions) || definitions.size() > 128)
                throw new ServiceException("工具定义无效");
            Set<String> names = new HashSet<>();
            for (Object definition : definitions) {
                JsonNode value = JSON.valueToTree(definition);
                boolean anthropic = TokenDanceProtocols.ANTHROPIC_MESSAGES.equals(config.getProtocol());
                if (!anthropic && !"function".equals(value.path("type").asText())
                        || anthropic && value.hasNonNull("type") && !"custom".equals(value.path("type").asText()))
                    throw new ServiceException("工具类型不支持");
                JsonNode function = TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(config.getProtocol()) ? value.path("function") : value;
                String name = function.path("name").asText();
                JsonNode schema = function.get(anthropic ? "input_schema" : "parameters");
                if (!name.matches(anthropic || reasoningLimit == DEEPSEEK_TURN_CHARS ? "[A-Za-z0-9_-]{1,128}" : "[A-Za-z0-9_-]{1,64}") || !names.add(name)
                        || (schema == null ? anthropic : !schema.isObject())) throw new ServiceException("工具定义无效");
            }
        }
        Object choice = request.getOptions() == null ? null : request.getOptions().get("tool_choice");
        if (choice != null && !"none".equals(choice) && !"auto".equals(choice)
                && (!(rawTools instanceof java.util.Collection<?> definitions) || definitions.isEmpty()))
            throw new ServiceException("缺少工具定义");
        Set<String> pending = new HashSet<>();
        Set<String> seen = new HashSet<>();
        boolean previous = TokenDanceProtocols.OPENAI_RESPONSES.equals(config.getProtocol())
                && request.getOptions() != null && request.getOptions().get("previous_response_id") instanceof String id && !id.isBlank();
        if (request.getMessages() == null) return;
        for (TextMessageItem item : request.getMessages()) {
            if (item == null) continue;
            String role = item.getRole() == null ? "user" : item.getRole().trim().toLowerCase(java.util.Locale.ROOT);
            item.setRole(role);
            if (!Set.of("system", "developer", "user", "assistant", "tool").contains(role))
                throw new ServiceException("消息角色不支持");
            if ("tool".equals(role)) {
                requireId(item.getToolCallId());
                if ((!pending.remove(item.getToolCallId()) && (!previous || seen.contains(item.getToolCallId()))) || !seen.add("result:" + item.getToolCallId())
                        || item.getContent() == null || item.getParts() != null && !item.getParts().isEmpty()
                        || hasCalls(item) || item.getReasoningContent() != null || item.getThinkingBlocks() != null || item.getResponseItems() != null)
                    throw new ServiceException("工具结果关联无效");
                continue;
            }
            if (!pending.isEmpty()) throw new ServiceException("工具结果尚未补齐");
            if (item.getToolCallId() != null) throw new ServiceException("工具结果角色无效");
            if (item.getToolError() != null) throw new ServiceException("工具结果角色无效");
            if (item.getResponseItems() != null) {
                if (!"assistant".equals(role) || !TokenDanceProtocols.OPENAI_RESPONSES.equals(config.getProtocol()))
                    throw new ServiceException("响应消息协议不符");
                validateResponseItems(item.getResponseItems());
                TextMessageItem restored = sync(JSON.createObjectNode().set("output", JSON.valueToTree(item.getResponseItems())), config.getProtocol());
                if (restored == null || !JSON.valueToTree(restored.getToolCalls()).equals(JSON.valueToTree(item.getToolCalls())))
                    throw new ServiceException("工具上下文不一致");
            }
            if (hasCalls(item)) {
                if (!"assistant".equals(role)) throw new ServiceException("工具调用角色无效");
                checkCalls(item);
                for (TextToolCall call : item.getToolCalls()) {
                    if (!seen.add(call.getId())) throw new ServiceException("工具标识重复");
                    pending.add(call.getId());
                }
            }
            if (item.getReasoningContent() != null && (!"assistant".equals(role)
                    || !TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(config.getProtocol())))
                throw new ServiceException("思考消息协议不符");
            if (item.getThinkingBlocks() != null) {
                if (!"assistant".equals(role) || !TokenDanceProtocols.ANTHROPIC_MESSAGES.equals(config.getProtocol()))
                    throw new ServiceException("思考消息协议不符");
                for (TextThinkingBlock block : item.getThinkingBlocks()) thinking(block);
            }
            if (string(item.getReasoningContent()).length() > reasoningLimit) throw new ServiceException("思考上下文过长");
        }
        if (!pending.isEmpty()) throw new ServiceException("工具结果尚未补齐");
    }

    public static void checkCalls(TextMessageItem item) {
        if (!hasCalls(item)) return;
        Set<String> ids = new HashSet<>();
        if (item.getToolCalls().size() > 128) throw new ServiceException("工具调用数量超限");
        for (TextToolCall call : item.getToolCalls()) {
            if (call == null) throw new ServiceException("工具调用结果无效");
            requireId(call.getId());
            if (!ids.add(call.getId()) || call.getName() == null || !call.getName().matches("[A-Za-z0-9_-]{1,128}")
                    || call.getArguments() == null || call.getArguments().length() > MAX_TURN_CHARS
                    || !tree(call.getArguments()).isObject()) throw new ServiceException("工具调用结果无效");
        }
    }

    public static List<Map<String, Object>> anthropicBlocks(TextMessageItem item) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (item.getThinkingBlocks() != null) for (TextThinkingBlock block : item.getThinkingBlocks()) blocks.add(thinking(block));
        if ("tool".equals(item.getRole())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_result"); result.put("tool_use_id", item.getToolCallId()); result.put("content", item.getContent());
            if (item.getToolError() != null) result.put("is_error", item.getToolError());
            blocks.add(result);
        } else if (hasCalls(item)) for (TextToolCall call : item.getToolCalls())
            blocks.add(Map.of("type", "tool_use", "id", call.getId(), "name", call.getName(), "input", tree(call.getArguments())));
        return blocks;
    }

    public static List<Map<String, Object>> responseCalls(TextMessageItem item) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (item.getResponseItems() != null) return item.getResponseItems();
        if ("tool".equals(item.getRole()))
            calls.add(Map.of("type", "function_call_output", "call_id", item.getToolCallId(), "output", item.getContent()));
        else if (hasCalls(item)) for (TextToolCall call : item.getToolCalls())
            calls.add(Map.of("type", "function_call", "call_id", call.getId(), "name", call.getName(), "arguments", call.getArguments()));
        return calls;
    }

    public static TextMessageItem sync(JsonNode root, String protocol) {
        TextMessageItem message = new TextMessageItem();
        message.setRole("assistant");
        List<TextToolCall> calls = new ArrayList<>();
        if (TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(protocol)) {
            JsonNode nativeMessage = root.path("choices").path(0).path("message");
            message.setContent(nativeMessage.path("content").asText(null));
            message.setReasoningContent(nativeMessage.path("reasoning_content").asText(null));
            for (JsonNode call : nativeMessage.path("tool_calls")) {
                if (!"function".equals(call.path("type").asText())) throw new ServiceException("工具类型不支持");
                calls.add(call(call.path("id").asText(), call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
            }
        } else if (TokenDanceProtocols.OPENAI_RESPONSES.equals(protocol)) {
            message.setResponseId(root.path("id").asText(null));
            for (JsonNode item : root.path("output")) if ("function_call".equals(item.path("type").asText()))
                calls.add(call(item.path("call_id").asText(), item.path("name").asText(), item.path("arguments").asText()));
            if (!calls.isEmpty()) {
                List<Map<String, Object>> output = JSON.convertValue(root.path("output"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
                validateResponseItems(output);
                message.setResponseItems(output);
            }
        } else {
            List<TextThinkingBlock> thoughts = new ArrayList<>();
            for (JsonNode item : root.path("content")) {
                String type = item.path("type").asText();
                if ("tool_use".equals(type)) calls.add(call(item.path("id").asText(), item.path("name").asText(), item.path("input").toString()));
                else if (Set.of("thinking", "redacted_thinking").contains(type)) {
                    TextThinkingBlock block = new TextThinkingBlock();
                    block.setType(type); block.setThinking(item.path("thinking").asText(null));
                    block.setSignature(item.path("signature").asText(null)); block.setData(item.path("data").asText(null));
                    thoughts.add(block);
                }
            }
            message.setThinkingBlocks(thoughts.isEmpty() ? null : thoughts);
        }
        message.setToolCalls(calls);
        checkCalls(message);
        if (!calls.isEmpty() && message.getThinkingBlocks() != null)
            for (TextThinkingBlock block : message.getThinkingBlocks()) thinking(block);
        return calls.isEmpty() ? null : message;
    }

    /** 每条流独立保存增量，只有完整终态后才将函数参数交给调用方。 */
    public static final class Accumulator {
        private final String protocol;
        private final int characterLimit;
        private final Map<Integer, com.fasterxml.jackson.databind.node.ObjectNode> blocks = new LinkedHashMap<>();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private JsonNode completed;
        private int chars;
        public Accumulator(String protocol) { this(protocol, MAX_TURN_CHARS); }
        public Accumulator(String protocol, int characterLimit) {
            this.protocol = protocol;
            this.characterLimit = characterLimit;
        }
        public void accept(JsonNode event) {
            if (TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(protocol)) {
                JsonNode delta = event.path("choices").path(0).path("delta");
                if (characterLimit == DEEPSEEK_TURN_CHARS) append(content, delta.path("content").asText(""));
                append(reasoning, delta.path("reasoning_content").asText(""));
                for (JsonNode call : delta.path("tool_calls")) {
                    int index = call.path("index").asInt(-1);
                    requireIndex(index);
                    var target = blocks.computeIfAbsent(index, key -> JSON.createObjectNode().put("type", "function"));
                    if (call.hasNonNull("type") && !"function".equals(call.path("type").asText())) throw new ServiceException("工具类型不支持");
                    copy(target, call, "id");
                    var function = target.withObject("/function");
                    copy(function, call.path("function"), "name");
                    add(function, "arguments", call.path("function").path("arguments").asText(""));
                }
            } else if (TokenDanceProtocols.OPENAI_RESPONSES.equals(protocol)) {
                if ("response.completed".equals(event.path("type").asText())) completed = event.path("response");
            } else {
                String type = event.path("type").asText();
                if ("content_block_start".equals(type)) {
                    int index = event.path("index").asInt(-1); requireIndex(index);
                    JsonNode block = event.path("content_block");
                    if (Set.of("tool_use", "thinking", "redacted_thinking").contains(block.path("type").asText())) {
                        count(block.toString().length());
                        if (blocks.putIfAbsent(index, (com.fasterxml.jackson.databind.node.ObjectNode) block.deepCopy()) != null)
                            throw new ServiceException("工具流顺序无效");
                    }
                } else if ("content_block_delta".equals(type)) {
                    var target = blocks.get(event.path("index").asInt(-1));
                    JsonNode delta = event.path("delta");
                    if (target != null) switch (delta.path("type").asText()) {
                        case "input_json_delta" -> add(target, "partial_json", delta.path("partial_json").asText(""));
                        case "thinking_delta" -> add(target, "thinking", delta.path("thinking").asText(""));
                        case "signature_delta" -> add(target, "signature", delta.path("signature").asText(""));
                        default -> { }
                    }
                }
            }
        }
        public TextMessageItem finish() {
            JsonNode root = completed;
            if (TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(protocol)) {
                var message = JSON.createObjectNode();
                message.set("tool_calls", JSON.valueToTree(blocks.values()));
                if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
                root = JSON.createObjectNode().set("choices", JSON.createArrayNode().add(JSON.createObjectNode().set("message", message)));
            } else if (TokenDanceProtocols.ANTHROPIC_MESSAGES.equals(protocol)) {
                for (var block : blocks.values()) if (block.has("partial_json")) {
                    block.set("input", tree(block.path("partial_json").asText())); block.remove("partial_json");
                }
                root = JSON.createObjectNode().set("content", JSON.valueToTree(blocks.values()));
            }
            return root == null ? null : sync(root, protocol);
        }

        /** 思考工具协议在没有函数调用的轮次也需要回传完整助手上下文。 */
        public TextMessageItem finishChatTurn() {
            TextMessageItem message = finish();
            if (message == null) {
                message = new TextMessageItem();
                message.setRole("assistant");
                message.setReasoningContent(reasoning.toString());
            }
            message.setContent(content.toString());
            return message;
        }
        private void copy(com.fasterxml.jackson.databind.node.ObjectNode target, JsonNode source, String key) {
            if (source.hasNonNull(key)) {
                String value = source.path(key).asText();
                if (target.hasNonNull(key) && !target.path(key).asText().equals(value)) throw new ServiceException("工具流标识冲突");
                count(value.length()); target.put(key, value);
            }
        }
        private void add(com.fasterxml.jackson.databind.node.ObjectNode target, String key, String value) {
            count(value.length()); target.put(key, target.path(key).asText("") + value);
        }
        private void append(StringBuilder target, String value) { count(value.length()); target.append(value); }
        private void count(int value) { chars += value; if (chars > characterLimit) throw new ServiceException("工具上下文过长"); }
        private void requireIndex(int index) { if (index < 0 || index >= 128) throw new ServiceException("工具序号无效"); }
    }

    private static Map<String, Object> thinking(TextThinkingBlock block) {
        if (block == null) throw new ServiceException("思考块无效");
        if ("thinking".equals(block.getType()) && block.getThinking() != null && block.getSignature() != null
                && !block.getSignature().isBlank() && block.getThinking().length() + block.getSignature().length() <= MAX_TURN_CHARS)
            return Map.of("type", "thinking", "thinking", block.getThinking(), "signature", block.getSignature());
        if ("redacted_thinking".equals(block.getType()) && block.getData() != null && !block.getData().isBlank()
                && block.getData().length() <= MAX_TURN_CHARS) return Map.of("type", "redacted_thinking", "data", block.getData());
        throw new ServiceException("思考签名无效");
    }
    private static void validateResponseItems(List<Map<String, Object>> items) {
        if (items.size() > 256 || JSON.valueToTree(items).toString().length() > MAX_TURN_CHARS)
            throw new ServiceException("工具上下文过长");
        for (Map<String, Object> item : items) {
            if (item == null) throw new ServiceException("响应内容无效");
            String type = String.valueOf(item.get("type"));
            Set<String> keys = switch (type) {
                case "reasoning" -> Set.of("type", "id", "summary", "encrypted_content", "status", "content");
                case "message" -> Set.of("type", "id", "role", "content", "status", "phase");
                case "function_call" -> Set.of("type", "id", "call_id", "name", "arguments", "status");
                default -> throw new ServiceException("响应工具类型不支持");
            };
            if (!keys.containsAll(item.keySet()) || "message".equals(type) && !"assistant".equals(item.get("role")))
                throw new ServiceException("响应内容无效");
        }
    }
    private static TextToolCall call(String id, String name, String arguments) {
        TextToolCall value = new TextToolCall(); value.setId(id); value.setName(name); value.setArguments(arguments); return value;
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,256}")) throw new ServiceException("工具标识无效");
    }
    private static String string(String value) { return value == null ? "" : value; }
    private static JsonNode tree(String value) {
        try { JsonNode node = JSON.readTree(value); if (node != null) return node; }
        catch (Exception ignored) { }
        throw new ServiceException("工具消息无效");
    }
}
