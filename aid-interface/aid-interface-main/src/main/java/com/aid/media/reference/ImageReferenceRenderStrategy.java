package com.aid.media.reference;

/**
 * 图片参考渲染策略（按厂商可插拔）。
 *
 * @author 视觉AID
 */
public interface ImageReferenceRenderStrategy
{
    /**
     * 本策略是否归属指定服务商。
     *
     * @param providerCode {@code aid_ai_provider.provider_code}
     * @return true=由本策略处理
     */
    boolean supportsProviderCode(String providerCode);

    /**
     * 渲染参考引用为 prompt + 参考图数组。
     *
     * @param ctx 渲染上下文（原 prompt + 富化引用清单 + 模型配置）
     * @return 渲染产物（finalPrompt + referenceImageUrls，严格同序）
     */
    ImageReferenceRenderPlan render(ImageReferenceRenderContext ctx);
}
