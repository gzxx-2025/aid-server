package com.aid.media.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.ResolvedImageReference;

/**
 * 图片参考渲染上下文（Provider 层构造，喂给 {@link ImageReferenceRenderStrategy}）。
 *
 * @author 视觉AID
 */
public class ImageReferenceRenderContext
{
    /** 原始 prompt（业务层产出，含 {@code @图片N[name]} 占位；可能尾部带 {@code ---参考图映射---} 段）。 */
    private final String originalPrompt;

    /** 富化引用清单（按 N 升序），来自 {@code StoryboardImageReferenceResolver.resolveRich}。 */
    private final List<ResolvedImageReference> references;

    /** 模型聚合配置：决定 providerCode 路由 + capability_json.maxReferenceImages。 */
    private final AiModelConfigVo modelConfig;

    /** 厂商默认参考图上限（仅在 capability_json 未配置时兜底）。 */
    private final int fallbackMaxReferenceImages;

    public ImageReferenceRenderContext(String originalPrompt, List<ResolvedImageReference> references,
                                       AiModelConfigVo modelConfig, int fallbackMaxReferenceImages)
    {
        this.originalPrompt = originalPrompt;
        this.references = references == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(references));
        this.modelConfig = modelConfig;
        this.fallbackMaxReferenceImages = fallbackMaxReferenceImages;
    }

    public String getOriginalPrompt()
    {
        return originalPrompt;
    }

    public List<ResolvedImageReference> getReferences()
    {
        return references;
    }

    public AiModelConfigVo getModelConfig()
    {
        return modelConfig;
    }

    public int getFallbackMaxReferenceImages()
    {
        return fallbackMaxReferenceImages;
    }

    /** 便捷取 providerCode（modelConfig 为空时返回 null）。 */
    public String getProviderCode()
    {
        return modelConfig == null ? null : modelConfig.getProviderCode();
    }
}
