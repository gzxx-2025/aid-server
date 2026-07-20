package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.OpenAiCompatibleConstants;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.OpenAiCompatiblePayloadResolver;
import com.aid.media.provider.OpenAiStyleChatStream;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextChatOpenAiPayloadBuilder;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.TextStreamCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容文本 Provider：统一承载各家 OpenAI Chat Completions 兼容的文本模型接入。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class GenericOpenAiCompatibleTextProviderClient implements TextProviderClient {

    @Override
    public String protocol() {
        return OpenAiCompatibleConstants.PROTOCOL_TEXT;
    }

    @Override
    public void streamChat(AiModelConfigVo modelConfig, MediaTextGenerateRequest request,
                           TextStreamCallbacks callbacks) throws IOException {
        String url = resolveUrlOrFail(modelConfig, callbacks, true);
        if (url == null) {
            return;
        }
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("OpenAI 兼容流式: apiKey 为空, providerCode={}, modelCode={}",
                    modelConfig == null ? null : modelConfig.getProviderCode(),
                    modelConfig == null ? null : modelConfig.getModelCode());
            callbacks.onError(OpenAiCompatibleConstants.ERROR_API_KEY_EMPTY, null);
            return;
        }
        String model = resolveEffectiveModel(modelConfig, request);
        List<Map<String, Object>> messages = TextChatOpenAiPayloadBuilder.buildMessageMaps(modelConfig, request);
        Map<String, Object> mergedOptions = OpenAiCompatiblePayloadResolver.mergeExtraBody(
                modelConfig.getExtraBodyJson(), modelConfig.getModelExtraBodyJson(), request.getOptions());
        // 结构化输出：模型声明支持且消息含 JSON 关键词时注入 response_format=json_object，避免格式错误
        mergedOptions = com.aid.media.provider.StructuredOutputSupport
                .applyJsonModeIfSupported(modelConfig, messages, mergedOptions);
        // 模型级 stream=false 时强制使用非流式上游请求；对外流式接口会在请求完成后一次性回传全文。
        if (isNonStreamConfigured(mergedOptions)) {
            log.info("OpenAI 兼容模型配置为非流式, providerCode={}, model={}",
                    modelConfig.getProviderCode(), model);
            emitSyncResult(chatSync(modelConfig, request), callbacks, modelConfig, model);
            return;
        }
        String body = TextChatOpenAiPayloadBuilder.buildChatCompletionsJsonBody(model, messages, true, mergedOptions);
        Map<String, String> extraHeaders = OpenAiCompatiblePayloadResolver.parseExtraHeaders(
                modelConfig.getExtraHeadersJson());
        log.info("OpenAI 兼容流式提交, providerCode={}, model={}, messagesSize={}, extraOptionsKeys={}",
                modelConfig.getProviderCode(), model, messages.size(),
                mergedOptions == null ? 0 : mergedOptions.size());
        OpenAiStyleChatStream.postSseStream(url, apiKey,
                modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), extraHeaders,
                body, callbacks);
    }

    @Override
    public ProviderSubmitResult chatSync(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        String url = OpenAiCompatiblePayloadResolver.buildApiUrl(
                modelConfig != null ? modelConfig.getBaseUrl() : null,
                modelConfig != null ? modelConfig.getApiSuffix() : null,
                modelConfig != null ? modelConfig.getExtraQueryJson() : null);
        if (url == null) {
            log.error("OpenAI 兼容非流式: base_url 或 api_suffix 缺失, providerCode={}, modelCode={}",
                    modelConfig == null ? null : modelConfig.getProviderCode(),
                    modelConfig == null ? null : modelConfig.getModelCode());
            return ProviderSubmitResult.builder()
                    .rawResponse(OpenAiCompatibleConstants.ERROR_BASE_URL_EMPTY)
                    .build();
        }
        String apiKey = modelConfig.getApiKey();
        if (StringUtils.isBlank(apiKey)) {
            return ProviderSubmitResult.builder()
                    .rawResponse(OpenAiCompatibleConstants.ERROR_API_KEY_EMPTY)
                    .build();
        }
        String model = resolveEffectiveModel(modelConfig, request);
        List<Map<String, Object>> messages = TextChatOpenAiPayloadBuilder.buildMessageMaps(modelConfig, request);
        Map<String, Object> mergedOptions = OpenAiCompatiblePayloadResolver.mergeExtraBody(
                modelConfig.getExtraBodyJson(), modelConfig.getModelExtraBodyJson(), request.getOptions());
        // 结构化输出：模型声明支持且消息含 JSON 关键词时注入 response_format=json_object，避免格式错误
        mergedOptions = com.aid.media.provider.StructuredOutputSupport
                .applyJsonModeIfSupported(modelConfig, messages, mergedOptions);
        String body = TextChatOpenAiPayloadBuilder.buildChatCompletionsJsonBody(model, messages, false, mergedOptions);
        Map<String, String> extraHeaders = OpenAiCompatiblePayloadResolver.parseExtraHeaders(
                modelConfig.getExtraHeadersJson());
        log.info("OpenAI 兼容非流式(NON_STREAM), providerCode={}, model={}, messagesSize={}",
                modelConfig.getProviderCode(), model, messages.size());
        return OpenAiStyleChatStream.postJsonSync(url, apiKey,
                modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), extraHeaders, body);
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        // 文本模型同步返回，无远端轮询，占位返回成功
        return ProviderTaskResult.builder()
                .status("SUCCEEDED")
                .build();
    }

    /**
     * 计算实际下发上游的模型名：经 {@link ModelCodeResolver} 解析（real_model_code 解耦展示码），最后兜底常量。
     */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StrUtil.isNotBlank(resolved)) {
            return resolved;
        }
        return OpenAiCompatibleConstants.DEFAULT_TEXT_MODEL;
    }

    /**
     * 模型或服务商 extra_body 显式配置 stream=false 时，强制走非流式调用。
     */
    private boolean isNonStreamConfigured(Map<String, Object> mergedOptions) {
        if (mergedOptions == null || !mergedOptions.containsKey("stream")) {
            return false;
        }
        return "false".equalsIgnoreCase(String.valueOf(mergedOptions.get("stream")));
    }

    /**
     * 将非流式完整响应适配为文本回调，兼容现有任务聚合与前端 SSE 接口。
     */
    private void emitSyncResult(ProviderSubmitResult result, TextStreamCallbacks callbacks,
                                AiModelConfigVo modelConfig, String model) {
        if (result == null) {
            log.error("OpenAI 兼容非流式返回为空, providerCode={}, model={}",
                    modelConfig.getProviderCode(), model);
            callbacks.onError("生成失败", null);
            return;
        }
        if (StringUtils.isNotBlank(result.getRawResponse())) {
            callbacks.onSseDataLine(result.getRawResponse());
        }
        if (StringUtils.isBlank(result.getDirectText())) {
            log.error("OpenAI 兼容非流式未返回正文, providerCode={}, model={}, raw={}",
                    modelConfig.getProviderCode(), model, result.getRawResponse());
            // 透传上游原始错误文本，保证限流/鉴权等错误能命中现有错误规则归一化，与流式路径口径一致
            callbacks.onError(StringUtils.defaultIfBlank(result.getRawResponse(), "生成失败"), null);
            return;
        }
        callbacks.onDelta(result.getDirectText());
        if (result.getUsage() != null && !result.getUsage().isEmpty()) {
            callbacks.onUsage(result.getUsage());
        }
        callbacks.onComplete();
    }

    /**
     * 流式入口的 URL 解析：缺配置时通过 callbacks 上报错误而不是抛异常，
     * 避免上层 SSE sink 收到未归一化的栈消息。
     */
    private String resolveUrlOrFail(AiModelConfigVo modelConfig, TextStreamCallbacks callbacks, boolean isStream) {
        String baseUrl = modelConfig != null ? modelConfig.getBaseUrl() : null;
        String apiSuffix = modelConfig != null ? modelConfig.getApiSuffix() : null;
        if (StringUtils.isBlank(baseUrl)) {
            log.error("OpenAI 兼容{}: base_url 为空, providerCode={}",
                    isStream ? "流式" : "非流式", modelConfig == null ? null : modelConfig.getProviderCode());
            callbacks.onError(OpenAiCompatibleConstants.ERROR_BASE_URL_EMPTY, null);
            return null;
        }
        if (StringUtils.isBlank(apiSuffix)) {
            log.error("OpenAI 兼容{}: api_suffix 为空, modelCode={}",
                    isStream ? "流式" : "非流式", modelConfig == null ? null : modelConfig.getModelCode());
            callbacks.onError(OpenAiCompatibleConstants.ERROR_API_SUFFIX_EMPTY, null);
            return null;
        }
        return OpenAiCompatiblePayloadResolver.buildApiUrl(baseUrl, apiSuffix,
                modelConfig.getExtraQueryJson());
    }
}
