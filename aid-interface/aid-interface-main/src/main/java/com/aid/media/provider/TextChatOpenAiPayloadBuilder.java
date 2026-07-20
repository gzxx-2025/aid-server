package com.aid.media.provider;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将业务请求与库表 system_prompt 转为 OpenAI Chat Completions 兼容的 messages JSON 片段。
 */
public final class TextChatOpenAiPayloadBuilder {

    private TextChatOpenAiPayloadBuilder() {
    }

    /**
     * 业务含义：组装多轮与单轮 prompt，与方舟/DashScope 兼容网关共用同一套语义。
     */
    public static List<Map<String, Object>> buildMessageMaps(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (request != null && CollectionUtil.isNotEmpty(request.getMessages())) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item == null || StringUtils.isBlank(item.getContent())) {
                    continue;
                }
                list.add(singleMessage(normalizeRole(item.getRole()), item.getContent()));
            }
        }
        if (request != null && StringUtils.isNotBlank(request.getPrompt())) {
            list.add(singleMessage("user", request.getPrompt()));
        }
        return list;
    }

    /**
     * 业务含义：生成 POST /chat/completions 的请求体 JSON 字符串，stream 由调用方决定。
     */
    public static String buildChatCompletionsJsonBody(String model,
                                                      List<Map<String, Object>> messages,
                                                      boolean stream,
                                                      Map<String, Object> options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", stream);
        // 流式模式下请求上游返回 token usage
        if (stream) {
            Map<String, Object> streamOptions = new LinkedHashMap<>();
            streamOptions.put("include_usage", true);
            body.put("stream_options", streamOptions);
        }
        if (options != null && !options.isEmpty()) {
            mergeOptions(body, options);
        }
        return JSONUtil.toJsonStr(body);
    }

    private static void mergeOptions(Map<String, Object> body, Map<String, Object> options) {
        for (Map.Entry<String, Object> e : options.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            // 跳过内部控制字段，不透传给上游
            if (Objects.equals("stream", e.getKey())
                    || Objects.equals("estimatedOutputChars", e.getKey())
                    || Objects.equals("maxOutputChars", e.getKey())) {
                continue;
            }
            // 跳过 Gemini 原生思考控制键：业务层（如资产提取）统一注入 thinking_level=disabled，
            // 仅 GeminiTextProviderClient 消费；OpenAI 兼容网关（Agnes/OpenAI 等）不认识该字段，
            // 透传会成为脏参数甚至被严格网关 400 拒绝。
            if (Objects.equals("thinking_level", e.getKey())
                    || Objects.equals("thinkingConfig", e.getKey())) {
                continue;
            }
            body.put(e.getKey(), e.getValue());
        }
    }

    private static Map<String, Object> singleMessage(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String normalizeRole(String role) {
        if (StringUtils.isBlank(role)) {
            return "user";
        }
        String r = role.trim().toLowerCase();
        if (Objects.equals("system", r) || Objects.equals("assistant", r) || Objects.equals("user", r)) {
            return r;
        }
        return "user";
    }
}
