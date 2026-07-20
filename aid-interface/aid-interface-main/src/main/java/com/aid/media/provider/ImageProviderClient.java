package com.aid.media.provider;

import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.domain.vo.AiModelConfigVo;

public interface ImageProviderClient {

    // 协议标识：用于服务层路由到具体实现。
    String protocol();

    // 协议匹配能力：默认按 protocol 精确匹配；实现类可按需要覆写为别名匹配。
    default boolean supportsProtocol(String protocol) {
        return protocol != null && protocol().equalsIgnoreCase(protocol);
    }

    /**
     * 服务商编码匹配能力：调度层按 {@code aid_ai_provider.provider_code} 强路由的依据。
     * 默认返回 {@code false}；各实现类声明自己归属的 provider_code（忽略大小写 + trim）。
     * 这是图片 Provider 的唯一路由依据，不再按模型名前缀猜测。
     *
     * @param providerCode 来自 {@code AiModelConfigVo.getProviderCode()}
     * @return 是否归属该 providerCode
     */
    default boolean supportsProviderCode(String providerCode) {
        return false;
    }

    // 提交图片生成任务：返回直出URL或providerTaskId。
    // 说明：具体走同步还是异步、请求体协议差异（如 messages/prompt）由实现类按模型名自行路由。
    ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request);

    // 查询图片任务状态：返回归一化状态与结果URL。
    // 说明：实现类需要兼容不同厂商/不同模型族的查询响应字段差异并统一输出。
    ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId);

    /**
     * 按业务场景翻译为厂商协议字段。
     *
     * @param request  图片生成请求（实现类可直接修改其 size / options）
     * @param scenario 场景标识，参见 {@link com.aid.media.constants.MediaImageScenario}；为 null/空时直接返回
     */
    default void applyScenarioOverrides(MediaImageGenerateRequest request, String scenario) {
        // 默认空实现：保留原 size / options 不变
    }
}
