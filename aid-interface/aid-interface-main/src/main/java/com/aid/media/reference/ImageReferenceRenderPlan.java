package com.aid.media.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图片参考渲染产物（对齐视频侧 {@code VideoReferencePlan} 风格）。
 *
 * @author 视觉AID
 */
public class ImageReferenceRenderPlan
{
    /** 最终下发模型的提示词（含厂商特定的参考图语义标注 / 说明段）。 */
    private final String finalPrompt;

    /** 按渲染顺序排列的参考图 URL 列表（与 finalPrompt 内标号严格对齐）。 */
    private final List<String> referenceImageUrls;

    private ImageReferenceRenderPlan(String finalPrompt, List<String> referenceImageUrls)
    {
        this.finalPrompt = finalPrompt;
        this.referenceImageUrls = referenceImageUrls == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(referenceImageUrls));
    }

    public static ImageReferenceRenderPlan of(String finalPrompt, List<String> referenceImageUrls)
    {
        return new ImageReferenceRenderPlan(finalPrompt, referenceImageUrls);
    }

    public String getFinalPrompt()
    {
        return finalPrompt;
    }

    public List<String> getReferenceImageUrls()
    {
        return referenceImageUrls;
    }
}
