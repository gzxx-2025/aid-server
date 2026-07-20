package com.aid.media.reference.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.media.constants.AgnesConstants;
import com.aid.media.reference.ImageReferenceRenderContext;
import com.aid.media.reference.ImageReferenceRenderPlan;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.ResolvedImageReference;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Agnes（OpenAI 兼容多图融合）图片参考渲染策略。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AgnesImageReferenceRenderStrategy extends AbstractImageReferenceRenderStrategy
{
    /** 参考图说明段前缀。 */
    private static final String REFERENCE_NOTE_PREFIX = "参考图说明：";

    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return providerCode != null && AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public ImageReferenceRenderPlan render(ImageReferenceRenderContext ctx)
    {
        String prompt = stripMappingSection(ctx.getOriginalPrompt());

        List<ResolvedImageReference> refRefs = pickReferenceType(ctx.getReferences());
        List<String> allUrls = new ArrayList<>();
        for (ResolvedImageReference r : refRefs)
        {
            allUrls.add(r.getUrl());
        }
        List<String> limitedUrls = limitReferenceUrls(allUrls, ctx, "Agnes");
        int retainCount = Math.min(limitedUrls.size(), refRefs.size());
        List<ResolvedImageReference> retained = new ArrayList<>(refRefs.subList(0, retainCount));

        Map<Integer, String> labelByN = buildEntityLabels(ctx.getReferences());
        for (ResolvedImageReference r : ctx.getReferences())
        {
            prompt = replacePlaceholder(prompt, r, labelByN.getOrDefault(r.getN(), entityLabel(r)));
        }
        // 占位替换后清掉残留的 @选择标记（@中近景/@平视/@50mm标准 等，与 Sanitizer 第 3 步对齐）
        prompt = StrUtil.trimToEmpty(stripStrayAtMarkers(prompt));

        if (!retained.isEmpty())
        {
            StringBuilder note = new StringBuilder("\n").append(REFERENCE_NOTE_PREFIX);
            for (int i = 0; i < retained.size(); i++)
            {
                if (i > 0) { note.append(", "); }
                ResolvedImageReference r = retained.get(i);
                note.append("图").append(i + 1).append('=').append(labelByN.getOrDefault(r.getN(), entityLabel(r)));
            }
            prompt = prompt + note;
        }

        List<String> referenceUrls = new ArrayList<>();
        for (ResolvedImageReference r : retained)
        {
            referenceUrls.add(r.getUrl());
        }

        int descCount = ctx.getReferences().size() - retainCount;
        log.info("Agnes 参考渲染: refTotal={}, 引用图={}, 描述/截断={}, referenceUrls={}",
                ctx.getReferences().size(), retainCount, descCount, referenceUrls.size());
        return ImageReferenceRenderPlan.of(prompt, referenceUrls);
    }
}
