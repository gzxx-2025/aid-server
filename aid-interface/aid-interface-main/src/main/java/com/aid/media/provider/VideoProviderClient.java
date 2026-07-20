package com.aid.media.provider;

import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.domain.vo.AiModelConfigVo;

public interface VideoProviderClient {

    // 协议标识：用于服务层路由到具体实现。
    String protocol();

    // 协议匹配能力：默认按 protocol 精确匹配；实现类可按需要覆写为别名匹配。
    default boolean supportsProtocol(String protocol) {
        return protocol != null && protocol().equalsIgnoreCase(protocol);
    }

    /**
     * 服务商编码匹配能力：调度层按 {@code aid_ai_provider.provider_code} 强路由的依据。
     * 默认返回 {@code false}；各实现类声明自己归属的 provider_code（忽略大小写 + trim）。
     * 这是视频 Provider 的唯一路由依据，不再按模型名前缀猜测。
     *
     * @param providerCode 来自 {@code AiModelConfigVo.getProviderCode()}
     * @return 是否归属该 providerCode
     */
    default boolean supportsProviderCode(String providerCode) {
        return false;
    }

    // 提交视频生成任务：返回直出URL或providerTaskId。
    ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request);

    // 查询视频任务状态：返回归一化状态与结果URL。
    ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId);
}
