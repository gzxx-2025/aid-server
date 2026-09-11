package com.aid.media.provider;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** 文本函数结果的紧凑存档与仅内存续轮上下文。 */
public final class TextToolResultSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "textToolMessage";
    private TextToolResultSupport() { }

    public static void capture(AidMediaTask task, TextMessageItem message) {
        if (message == null || message.getToolCalls() == null || message.getToolCalls().isEmpty()) return;
        try {
            var live = JSON.valueToTree(message);
            if (live.toString().length() > 200_000) throw new ServiceException("工具上下文过长");
            var stored = (com.fasterxml.jackson.databind.node.ObjectNode) live.deepCopy();
            stored.remove(java.util.List.of("reasoningContent", "thinkingBlocks", "responseItems", "content", "parts"));
            String snapshot = JSON.createObjectNode().set(KEY, stored).toString();
            if (!snapshot.equals(MediaTaskPayloadSanitizer.sanitizeForStorage(snapshot))) throw new ServiceException("工具结果含内嵌文件");
            task.setLiveTextTurn(JSON.convertValue(live, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            task.setResponseJson(snapshot);
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("工具结果无效"); }
    }

    public static TextMessageItem read(AidMediaTask task) {
        if (!"TEXT".equals(task.getMediaType()) || !"SUCCEEDED".equals(task.getStatus())) return null;
        try {
            if (task.getLiveTextTurn() != null) return JSON.convertValue(task.getLiveTextTurn(), TextMessageItem.class);
            if (task.getResponseJson() == null) return null;
            var root = JSON.readTree(task.getResponseJson());
            if (!root.path(KEY).isObject()) return null;
            TextMessageItem message = JSON.treeToValue(root.path(KEY), TextMessageItem.class);
            message.setContent(task.getResultText());
            return message;
        } catch (Exception ignored) { return null; }
    }

    /** 终态归档只保留本组件生成的函数结果摘要，不保留原始上游响应或思考上下文。 */
    public static String compactSnapshot(String value) {
        if (value == null || value.length() > 200_000) return "";
        try {
            var root = JSON.readTree(value);
            if (root == null || root.size() != 1 || !root.path(KEY).isObject()) return "";
            TextMessageItem message = JSON.treeToValue(root.path(KEY), TextMessageItem.class);
            if (!"assistant".equals(message.getRole()) || message.getToolCalls() == null || message.getToolCalls().isEmpty()) return "";
            AidMediaTask compact = new AidMediaTask();
            capture(compact, message);
            return compact.getResponseJson();
        } catch (Exception ignored) { return ""; }
    }
}
