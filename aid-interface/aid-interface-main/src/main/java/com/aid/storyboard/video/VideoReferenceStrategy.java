package com.aid.storyboard.video;

import com.aid.domain.vo.AiModelConfigVo;

/**
 * 分镜视频生成参考素材装配策略：按厂商或协议差异化处理多模态参考图，由 Spring 自动发现。
 *
 * @author 视觉AID
 */
public interface VideoReferenceStrategy
{
    /**
     * 是否归属指定 provider_code（策略路由的唯一依据，忽略大小写 + trim）。
     *
     * @param providerCode 厂商编码
     * @return 是否归属
     */
    default boolean supportsProviderCode(String providerCode)
    {
        return false;
    }

    /**
     * 是否匹配完整模型配置。默认保持既有 provider_code 路由语义；通用协议策略可按 protocol 匹配。
     *
     * @param modelConfig 模型聚合配置
     * @return 是否归属
     */
    default boolean supportsModelConfig(AiModelConfigVo modelConfig)
    {
        return modelConfig != null && supportsProviderCode(modelConfig.getProviderCode());
    }

    /**
     * 是否为通用兜底策略（providerCode 未命中任何专用策略时使用，容器内至多一个）。
     */
    default boolean isDefault()
    {
        return false;
    }

    /**
     * 按本厂商 / 模型能力装配最终 prompt + 参考图 + 首帧。
     *
     * @param ctx 装配上下文
     * @return 装配产物（最终 prompt / referenceImages / 首帧 URL）
     */
    VideoReferencePlan assemble(VideoReferenceContext ctx);

    /**
     * 便捷判断：模型是否支持多图参考。
     */
    default boolean supportsMultiImage(AiModelConfigVo modelConfig)
    {
        return modelConfig != null && Boolean.TRUE.equals(modelConfig.getSupportsMultiImageInput());
    }
}
