package com.aid.media.provider;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文本生成协议适配器：与 {@link ImageProviderClient} 同级，由编排层按 protocol / 模型名路由。
 */
public interface TextProviderClient {

    // 业务含义：同步 submit 聚合时允许写入 raw 的最大字符数，与流式接口审计上限一致。
    int TEXT_SYNC_RAW_SNAPSHOT_CAP = 100_000;

    // 业务含义：落库 aid_media_task.protocol，供扣费与排障追踪。
    String protocol();

    // 业务含义：与 protocol() 忽略大小写相等时命中显式协议路由。
    default boolean supportsProtocol(String protocol) {
        return protocol != null && protocol().equalsIgnoreCase(protocol);
    }

    // 业务含义：模型名包含协议关键字（去掉 -text）时的弱匹配，用于多模型族自动选路。
    default boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String marker = protocol().toLowerCase()
            .replace("-text", "")
            .replace("_text", "");
        return modelName.toLowerCase().contains(marker);
    }

    // 业务含义：上游一律走流式拉取，本方法将增量聚合为整段后写入 directText（同步 JSON 接口复用）。
    default ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        StringBuilder aggregated = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        AtomicReference<String> errorRef = new AtomicReference<>();
        // 业务含义：捕获 onUsage 回调（input_tokens/output_tokens/total_tokens），
        // 同步 submit 路径同样要带出真实 token usage 给 settleBilling，避免按预扣封顶结算。
        AtomicReference<Map<String, Object>> usageRef = new AtomicReference<>();
        final int maxRawChars = TEXT_SYNC_RAW_SNAPSHOT_CAP;
        try {
            streamChat(modelConfig, request, new TextStreamCallbacks() {
                @Override
                public void onDelta(String textDelta) {
                    if (textDelta != null) {
                        aggregated.append(textDelta);
                    }
                }

                @Override
                public void onSseDataLine(String dataLine) {
                    if (raw.length() < maxRawChars && dataLine != null) {
                        int room = maxRawChars - raw.length();
                        if (room <= 0) {
                            return;
                        }
                        String chunk = dataLine.length() <= room ? dataLine : dataLine.substring(0, room);
                        raw.append(chunk).append('\n');
                    }
                }

                @Override
                public void onError(String message, Throwable cause) {
                    if (errorRef.get() == null) {
                        errorRef.set(StringUtils.defaultIfBlank(message, cause != null ? cause.getMessage() : "流式错误"));
                    }
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onUsage(Map<String, Object> usage) {
                    if (usage != null && !usage.isEmpty()) {
                        usageRef.set(usage);
                    }
                }
            });
        } catch (IOException e) {
            return ProviderSubmitResult.builder()
                .rawResponse(e.getMessage())
                .build();
        }
        if (errorRef.get() != null) {
            return ProviderSubmitResult.builder()
                .rawResponse(errorRef.get())
                .build();
        }
        String rawStr = raw.length() >= maxRawChars
            ? raw + "\n...[truncated]"
            : raw.toString();
        return ProviderSubmitResult.builder()
            .directText(aggregated.toString())
            .rawResponse(rawStr)
            .usage(usageRef.get())
            .build();
    }

    // 业务含义：对上游发起流式 Chat Completions 并在回调中吐出增量。
    void streamChat(AiModelConfigVo modelConfig, MediaTextGenerateRequest request, TextStreamCallbacks callbacks) throws IOException;

    /**
     * 普通非流式文本生成（stream=false）：一次 HTTP 请求拿到完整响应，稳定解析 usage。
     * 适用于提取/billingExempt 等需要稳定 token 用量的场景，不依赖 SSE 末尾 usage chunk。
     * 默认实现委托 submit()（流式聚合），各 provider 可覆盖为真正的非流式调用。
     */
    default ProviderSubmitResult chatSync(AiModelConfigVo modelConfig, MediaTextGenerateRequest request) {
        return submit(modelConfig, request);
    }

    // 业务含义：同步 Chat 无远端轮询时占位返回成功，保持接口形状统一。
    ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId);
}
