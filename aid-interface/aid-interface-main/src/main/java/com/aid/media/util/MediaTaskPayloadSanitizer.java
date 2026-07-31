package com.aid.media.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aid.common.exception.ServiceException;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 媒体任务数据库载荷治理工具。
 *
 * <p>数据库只保存任务业务字段和必要的调度快照，图片、音频、视频等文件内容必须先上传对象存储，
 * 禁止以 Base64、data URI、超长十六进制等形式写入 request_json、response_json 或 result_text。</p>
 */
@Slf4j
public final class MediaTaskPayloadSanitizer {

    /** 无字段语义时判定内嵌二进制的最小字符数，避免把普通短编码误判为文件。 */
    private static final int BINARY_TEXT_MIN_LENGTH = 1024;

    /** 字段名已明确表示二进制时使用更小阈值，连小图标也不能写入数据库。 */
    private static final int BINARY_FIELD_MIN_LENGTH = 128;

    /** 无法安全局部清洗的文本统一替换为固定业务标记。 */
    private static final String BINARY_OMITTED = "[内嵌文件已移除]";

    /** 常见二进制响应字段名；比较前会去除下划线和中划线并转小写。 */
    private static final Set<String> BINARY_FIELD_NAMES = Set.of(
        "base64",
        "b64json",
        "imagebase64",
        "audiobase64",
        "videobase64",
        "filebase64",
        "filecontent",
        "imagedata",
        "audiodata",
        "videodata",
        "inlinedata",
        "bytes"
    );

    /** 业务扇入完成后允许从终态请求快照移除的上下文键。 */
    private static final Set<String> FAN_IN_CONTEXT_KEYS = Set.of(
        "sbzImageGenCtx",
        "sbzVideoGenCtx"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MediaTaskPayloadSanitizer() {
    }

    /**
     * 序列化任务请求并阻止内嵌文件进入任务表。
     *
     * @param request 图片、视频、音频、文本或合成请求
     * @return 可安全写入 request_json 的 JSON
     */
    public static String serializeRequest(Object request) {
        String json = JSONUtil.toJsonStr(request);
        if (containsEmbeddedBinary(json)) {
            log.error("媒体任务请求包含内嵌文件，拒绝写入数据库, payloadChars={}", StrUtil.length(json));
            throw new ServiceException("禁止Base64入参");
        }
        return json;
    }

    /**
     * 清洗厂商原始响应或模型文本，保证二进制内容不会进入数据库。
     *
     * <p>合法 JSON 会递归替换二进制字符串并保留状态、usage、URL 等排障字段；
     * 非 JSON 文本一旦检测到内嵌文件则整体替换，避免不完整的正则清洗产生残留。</p>
     *
     * @param payload 待落库文本
     * @return 无内嵌文件的安全文本
     */
    public static String sanitizeForStorage(String payload) {
        if (StrUtil.isBlank(payload)) {
            return payload;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            if (root == null) {
                return payload;
            }
            if (root.isTextual() && looksLikeEmbeddedBinary(root.asText(), null)) {
                log.error("媒体任务JSON字符串包含内嵌文件，已阻止落库, payloadChars={}", payload.length());
                return BINARY_OMITTED;
            }
            boolean changed = sanitizeNode(root, null);
            return changed ? OBJECT_MAPPER.writeValueAsString(root) : payload;
        } catch (Exception parseException) {
            if (looksLikeEmbeddedBinary(payload, null)) {
                log.error("媒体任务非JSON载荷包含内嵌文件，已阻止落库, payloadChars={}", payload.length());
                return BINARY_OMITTED;
            }
            return payload;
        }
    }

    /**
     * 媒体任务进入终态后压缩可重放请求。
     *
     * <p>QUEUED/PENDING/PROCESSING 仍需完整请求支持进程重启后拉起，绝不压缩。SUCCEEDED/FAILED
     * 已不再重放上游请求，因此移除 prompt、taskPromptDigest、文本 messages 以及仅供 Provider
     * 提交使用的 referenceManifest；模型、业务关联、参考图 URL 和小型生成参数继续留库。</p>
     *
     * <p>分镜 {@code sbzImageGenCtx}/{@code sbzVideoGenCtx} 暂时保留，必须等业务扇入消费完成后
     * 再调用 {@link #removeConsumedFanInContext(String, String)} 删除。</p>
     *
     * @param mediaType 任务媒体类型
     * @param status 任务状态
     * @param requestJson 原始请求快照
     * @return 终态紧凑快照；非终态时原样返回
     */
    public static String compactTerminalRequest(String mediaType, String status, String requestJson) {
        boolean terminal = Objects.equals(status, MediaTaskStatus.SUCCEEDED.name())
            || Objects.equals(status, MediaTaskStatus.FAILED.name());
        if (!terminal || StrUtil.isBlank(requestJson)) {
            return requestJson;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestJson);
            if (!(root instanceof ObjectNode objectNode)) {
                log.error("媒体任务终态请求快照不是JSON对象，改用紧凑标记");
                return compactFallback();
            }
            if (objectNode.path("payloadCompacted").asBoolean(false)) {
                return requestJson;
            }
            if (Objects.equals(mediaType, MediaType.TEXT.name())) {
                JsonNode messages = objectNode.get("messages");
                int messageCount = messages instanceof ArrayNode ? messages.size() : 0;
                objectNode.remove("messages");
                objectNode.put("messageCount", messageCount);
            }
            if (Objects.equals(mediaType, MediaType.TEXT.name())
                || Objects.equals(mediaType, MediaType.IMAGE.name())
                || Objects.equals(mediaType, MediaType.VIDEO.name())) {
                // 完整 prompt 只用于提交上游；终态后任务表 prompt 摘要与 request_hash 足够定位。
                objectNode.remove("prompt");
                objectNode.remove("taskPromptDigest");
                JsonNode options = objectNode.get("options");
                if (options instanceof ObjectNode optionObject) {
                    optionObject.remove("referenceManifest");
                }
            }
            if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
                // 配音正文已落在业务表；终态只保留 audioFormat 等生成参数供 OSS 后缀与资产快照使用。
                objectNode.remove("ttsText");
            }
            objectNode.put("payloadCompacted", true);
            return OBJECT_MAPPER.writeValueAsString(objectNode);
        } catch (Exception exception) {
            log.error("媒体任务终态请求快照压缩失败，改用紧凑标记, error={}", exception.getMessage());
            return compactFallback();
        }
    }

    /**
     * 兼容旧调用名，统一转入全媒体终态压缩。
     */
    public static String compactTerminalTextRequest(String mediaType, String status, String requestJson) {
        return compactTerminalRequest(mediaType, status, requestJson);
    }

    /**
     * 判断请求快照是否已执行终态压缩。
     */
    public static boolean isPayloadCompacted(String requestJson) {
        if (StrUtil.isBlank(requestJson)) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestJson);
            return root != null && root.path("payloadCompacted").asBoolean(false);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 业务扇入成功或失败收口后，移除已消费的分镜上下文。
     *
     * @param requestJson 终态请求快照
     * @param contextKey 仅允许 sbzImageGenCtx / sbzVideoGenCtx
     * @return 删除上下文后的请求快照；解析失败时返回原文
     */
    public static String removeConsumedFanInContext(String requestJson, String contextKey) {
        if (StrUtil.isBlank(requestJson) || !FAN_IN_CONTEXT_KEYS.contains(contextKey)) {
            return requestJson;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(requestJson);
            if (!(root instanceof ObjectNode objectNode)) {
                return requestJson;
            }
            JsonNode options = objectNode.get("options");
            if (!(options instanceof ObjectNode optionObject) || !optionObject.has(contextKey)) {
                return requestJson;
            }
            optionObject.remove(contextKey);
            return OBJECT_MAPPER.writeValueAsString(objectNode);
        } catch (Exception exception) {
            log.error("媒体任务扇入上下文清理失败, contextKey={}, error={}",
                contextKey, exception.getMessage());
            return requestJson;
        }
    }

    /**
     * 判断 JSON 或普通文本中是否包含文件型编码。
     */
    private static boolean containsEmbeddedBinary(String payload) {
        if (StrUtil.isBlank(payload)) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            return root != null && containsEmbeddedBinary(root, null);
        } catch (Exception ignored) {
            return looksLikeEmbeddedBinary(payload, null);
        }
    }

    private static boolean containsEmbeddedBinary(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return looksLikeEmbeddedBinary(node.asText(), fieldName);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (containsEmbeddedBinary(field.getValue(), field.getKey())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsEmbeddedBinary(child, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 原地递归清洗 JSON；返回是否发生替换。
     */
    private static boolean sanitizeNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return false;
        }
        boolean changed = false;
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value != null && value.isTextual()
                    && looksLikeEmbeddedBinary(value.asText(), field.getKey())) {
                    objectNode.put(field.getKey(), BINARY_OMITTED);
                    changed = true;
                    continue;
                }
                changed = sanitizeNode(value, field.getKey()) || changed;
            }
            return changed;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode child = arrayNode.get(index);
                if (child != null && child.isTextual()
                    && looksLikeEmbeddedBinary(child.asText(), fieldName)) {
                    arrayNode.set(index, OBJECT_MAPPER.getNodeFactory().textNode(BINARY_OMITTED));
                    changed = true;
                    continue;
                }
                changed = sanitizeNode(child, fieldName) || changed;
            }
        }
        return changed;
    }

    private static boolean looksLikeEmbeddedBinary(String value, String fieldName) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        String compact = value.trim();
        String lower = compact.toLowerCase(Locale.ROOT);
        int dataUriStart = lower.indexOf("data:");
        int dataUriPayload = lower.indexOf(";base64,", Math.max(dataUriStart, 0));
        if (dataUriStart >= 0 && dataUriPayload > dataUriStart) {
            return true;
        }
        int threshold = isBinaryField(fieldName) ? BINARY_FIELD_MIN_LENGTH : BINARY_TEXT_MIN_LENGTH;
        if (compact.length() < threshold) {
            return false;
        }
        return looksLikeBase64(compact)
            || looksLikeHexBinary(compact)
            || containsEncodedToken(compact, threshold);
    }

    private static boolean isBinaryField(String fieldName) {
        if (StrUtil.isBlank(fieldName)) {
            return false;
        }
        String normalized = fieldName.replace("_", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
        return BINARY_FIELD_NAMES.contains(normalized);
    }

    /**
     * 文件 Base64 通常是一个几乎无空白的超长 token；允许标准与 URL-safe 字符。
     */
    private static boolean looksLikeBase64(String value) {
        int encodedChars = 0;
        int paddingChars = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            boolean encoded = current >= 'A' && current <= 'Z'
                || current >= 'a' && current <= 'z'
                || current >= '0' && current <= '9'
                || current == '+'
                || current == '/'
                || current == '-'
                || current == '_';
            if (encoded) {
                encodedChars++;
                continue;
            }
            if (current == '=') {
                paddingChars++;
                continue;
            }
            return false;
        }
        return encodedChars >= BINARY_TEXT_MIN_LENGTH
            || encodedChars >= BINARY_FIELD_MIN_LENGTH && paddingChars <= 2;
    }

    /**
     * 部分语音厂商用超长 hex 返回音频，性质与 Base64 相同，也不能作为响应快照落库。
     */
    private static boolean looksLikeHexBinary(String value) {
        if (value.length() < BINARY_TEXT_MIN_LENGTH * 2 || value.length() % 2 != 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean hex = current >= '0' && current <= '9'
                || current >= 'a' && current <= 'f'
                || current >= 'A' && current <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 非 JSON 的 SSE/错误文本可能在普通字符中夹带 Base64；扫描连续编码 token，不能只判断整个字符串。
     */
    private static boolean containsEncodedToken(String value, int threshold) {
        int base64Run = 0;
        int hexRun = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean base64 = current >= 'A' && current <= 'Z'
                || current >= 'a' && current <= 'z'
                || current >= '0' && current <= '9'
                || current == '+'
                || current == '/'
                || current == '-'
                || current == '_'
                || current == '=';
            base64Run = base64 ? base64Run + 1 : 0;
            if (base64Run >= threshold) {
                return true;
            }

            boolean hex = current >= '0' && current <= '9'
                || current >= 'a' && current <= 'f'
                || current >= 'A' && current <= 'F';
            hexRun = hex ? hexRun + 1 : 0;
            if (hexRun >= BINARY_TEXT_MIN_LENGTH * 2) {
                return true;
            }
        }
        return false;
    }

    private static String compactFallback() {
        ObjectNode fallback = OBJECT_MAPPER.createObjectNode();
        fallback.put("messageCount", 0);
        fallback.put("payloadCompacted", true);
        return fallback.toString();
    }
}
