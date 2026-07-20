package com.aid.media.provider;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;

/**
 * 语音合成（TTS）服务商客户端接口。
 * 与 {@link ImageProviderClient} / {@link VideoProviderClient} 并列，
 * 遵循"协议标识 + 模型匹配 + submit/query"统一骨架。
 *
 * @author 视觉AID
 */
public interface AudioProviderClient {

    /** 协议标识：用于 {@link com.aid.aid.domain.media.AidMediaTask#getProtocol} 路由到具体实现 */
    String protocol();

    /**
     * 协议匹配能力：默认按 {@link #protocol()} 精确匹配；
     * 实现类可按需覆写为别名匹配。
     */
    default boolean supportsProtocol(String protocol) {
        return protocol != null && protocol().equalsIgnoreCase(protocol);
    }

    /**
     * 模型匹配能力：默认按"模型名包含协议关键字"弱匹配。
     */
    default boolean supportsModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String marker = protocol().toLowerCase()
                .replace("-audio", "")
                .replace("_audio", "")
                .replace("-tts", "")
                .replace("_tts", "");
        return modelName.toLowerCase().contains(marker);
    }

    /**
     * 服务商编码匹配能力：用于调度层按 {@code providerCode} 强路由。
     * 默认返回 {@code false}；具体实现类声明自己支持哪些 providerCode（忽略大小写 + trim）。
     * 调度层优先按此方法路由，命中后不再走 {@link #supportsModel(String)} 弱匹配。
     *
     * @param providerCode 来自 {@code AiModelConfigVo.getProviderCode()}
     * @return 是否支持该 providerCode
     */
    default boolean supportsProviderCode(String providerCode) {
        return false;
    }

    /**
     * 提交 TTS 任务（异步），返回 providerTaskId。
     * 豆包 TTS 明确为异步模式，不会同步返回直出 URL；
     * 实现需在 {@link ProviderSubmitResult#providerTaskId} 回填厂商 task_id，
     * 供调度中心后续 query 使用。
     */
    ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaAudioGenerateRequest request);

    /**
     * 查询 TTS 任务状态，返回归一化 {@link ProviderTaskResult}。
     *
     *   - status ∈ {PROCESSING, SUCCEEDED, FAILED}
     *   - SUCCEEDED 时 {@link ProviderTaskResult#resultUrl} 必须填厂商返回的 audio_url
     *   - 上游仍在处理时，必须返回 PROCESSING 以继续轮询
     *
     */
    ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId);
}
