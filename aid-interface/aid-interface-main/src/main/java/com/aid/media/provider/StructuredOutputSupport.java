package com.aid.media.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 文本模型结构化输出（JSON Mode）统一注入工具。
 * <p>
 * 模型在 {@code capability_json} 声明 {@code "supportsJsonObject": true} 且本次请求的
 * system / user 消息中包含 "JSON" 关键词（官方硬性前置条件，不区分大小写）时，自动注入
 * {@code response_format={"type":"json_object"}}，让上游直接返回可解析的标准 JSON，
 * 避免 ```json 包裹等多余文本导致下游解析失败。
 * <p>
 * 规则（与阿里百炼《结构化输出》官方文档对齐，OpenAI 兼容协议通用）：
 * <ol>
 *   <li>业务显式传入 response_format 时尊重业务配置，不覆盖；</li>
 *   <li>capability 未打标的模型完全不受影响（方舟 Seed 2.x Pro 等官方无此能力的模型不得打标）；</li>
 *   <li>消息不含 "JSON" 关键词时不注入——官方会直接报错
 *       {@code 'messages' must contain the word 'json' in some form}；</li>
 *   <li>注入时移除 max_tokens / max_completion_tokens：官方要求开启结构化输出时禁用，
 *       否则 JSON 可能被截断产生无效输出（移除后按模型最大输出长度兜底）。</li>
 * </ol>
 */
@Slf4j
public final class StructuredOutputSupport {

    private StructuredOutputSupport() {
    }

    /** capability_json 中的 JSON Mode 支持标记键 */
    public static final String CAPABILITY_SUPPORTS_JSON_OBJECT = "supportsJsonObject";

    /** OpenAI 兼容请求体字段：返回内容格式 */
    private static final String KEY_RESPONSE_FORMAT = "response_format";

    /** response_format.type 取值：JSON 模式 */
    private static final String TYPE_JSON_OBJECT = "json_object";

    /** 官方要求开启结构化输出时禁用的输出长度限制字段 */
    private static final String KEY_MAX_TOKENS = "max_tokens";
    private static final String KEY_MAX_COMPLETION_TOKENS = "max_completion_tokens";

    /** 官方前置条件关键词：system/user 消息需包含（不区分大小写） */
    private static final String KEYWORD_JSON = "json";

    /**
     * 按模型能力与官方前置条件注入 JSON Mode。
     *
     * @param modelConfig   模型聚合配置（读 capability_json.supportsJsonObject）
     * @param messages      已组装的 OpenAI 兼容 messages（检测 system/user 是否含 JSON 关键词）
     * @param mergedOptions extra_body 与业务 options 合并后的请求参数（可为 null）
     * @return 注入后的参数 Map；无需注入时原样返回入参
     */
    public static Map<String, Object> applyJsonModeIfSupported(AiModelConfigVo modelConfig,
                                                               List<Map<String, Object>> messages,
                                                               Map<String, Object> mergedOptions) {
        // 业务显式配置 response_format：尊重业务，不覆盖
        if (mergedOptions != null && mergedOptions.containsKey(KEY_RESPONSE_FORMAT)) {
            return mergedOptions;
        }
        if (!supportsJsonObject(modelConfig)) {
            return mergedOptions;
        }
        if (!messagesContainJsonKeyword(messages)) {
            // 官方硬性前置：消息必须含 JSON 关键词，否则上游 400；创作类纯文本调用天然跳过
            return mergedOptions;
        }
        Map<String, Object> out = mergedOptions == null ? new LinkedHashMap<>() : mergedOptions;
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", TYPE_JSON_OBJECT);
        out.put(KEY_RESPONSE_FORMAT, responseFormat);
        // 官方要求：开启结构化输出时禁用输出长度限制，防止 JSON 中途截断产生无效输出
        Object removedMaxTokens = out.remove(KEY_MAX_TOKENS);
        Object removedMaxCompletion = out.remove(KEY_MAX_COMPLETION_TOKENS);
        log.info("结构化输出注入 JSON Mode: modelCode={}, 移除max_tokens={}, 移除max_completion_tokens={}",
                modelConfig.getModelCode(), removedMaxTokens, removedMaxCompletion);
        return out;
    }

    /**
     * 读 capability_json.supportsJsonObject 标记；缺失、解析失败均视为不支持。
     */
    private static boolean supportsJsonObject(AiModelConfigVo modelConfig) {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getCapabilityJson())) {
            return false;
        }
        try {
            JSONObject capability = JSONUtil.parseObj(modelConfig.getCapabilityJson());
            return Boolean.TRUE.equals(capability.getBool(CAPABILITY_SUPPORTS_JSON_OBJECT, false));
        } catch (Exception e) {
            // capability 解析失败按不支持处理，不阻断文本主链路
            log.warn("capability_json 解析失败，JSON Mode 按不支持处理: modelCode={}, err={}",
                    modelConfig.getModelCode(), e.getMessage());
            return false;
        }
    }

    /**
     * 检测 system / user 消息内容是否包含 "JSON" 关键词（不区分大小写）。
     */
    private static boolean messagesContainJsonKeyword(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            Object role = message.get("role");
            String roleStr = role == null ? "" : String.valueOf(role);
            if (!"system".equals(roleStr) && !"user".equals(roleStr)) {
                continue;
            }
            Object content = message.get("content");
            if (content instanceof String text
                    && text.toLowerCase(Locale.ROOT).contains(KEYWORD_JSON)) {
                return true;
            }
        }
        return false;
    }

    /** Gemini generationConfig 字段：输出 MIME 类型（application/json = JSON Mode） */
    private static final String KEY_RESPONSE_MIME_TYPE = "responseMimeType";

    /** Gemini JSON Mode 的 MIME 取值 */
    private static final String MIME_APPLICATION_JSON = "application/json";

    /**
     * Gemini 原生形态的 JSON Mode 注入：模型打标 {@code supportsJsonObject} 且本次请求文本含
     * "JSON" 关键词（与 OpenAI 形态同一启发口径：业务要 JSON 的提示词均含该词）时，
     * 在 generationConfig 注入 {@code responseMimeType=application/json}，
     * 让上游直接返回标准 JSON（官方 Structured Outputs 的 JSON 模式）。
     * 调用方已显式配置 responseMimeType / responseSchema 时不覆盖。
     *
     * @param modelConfig      模型聚合配置（读 capability_json.supportsJsonObject）
     * @param hasJsonKeyword   请求文本（system/user/prompt）是否含 JSON 关键词
     * @param generationConfig Gemini generationConfig（原地注入）
     */
    public static void applyGeminiJsonModeIfSupported(AiModelConfigVo modelConfig,
                                                      boolean hasJsonKeyword,
                                                      Map<String, Object> generationConfig) {
        if (generationConfig == null || !hasJsonKeyword) {
            return;
        }
        if (generationConfig.containsKey(KEY_RESPONSE_MIME_TYPE)
                || generationConfig.containsKey("responseSchema")
                || generationConfig.containsKey("responseJsonSchema")) {
            return;
        }
        if (!supportsJsonObject(modelConfig)) {
            return;
        }
        generationConfig.put(KEY_RESPONSE_MIME_TYPE, MIME_APPLICATION_JSON);
        log.info("Gemini 结构化输出注入 responseMimeType=application/json: modelCode={}",
                modelConfig.getModelCode());
    }

    /**
     * 判断纯文本是否含 "JSON" 关键词（不区分大小写），供非 OpenAI messages 形态的 Provider 复用。
     */
    public static boolean textContainsJsonKeyword(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(KEYWORD_JSON);
    }
}
