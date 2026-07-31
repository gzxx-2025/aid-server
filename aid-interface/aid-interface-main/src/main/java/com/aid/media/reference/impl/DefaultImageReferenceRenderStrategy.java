package com.aid.media.reference.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aid.media.reference.ImageReferenceRenderContext;
import com.aid.media.reference.ImageReferenceRenderPlan;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.ResolvedImageReference;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 默认图片参考渲染策略（兜底）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class DefaultImageReferenceRenderStrategy extends AbstractImageReferenceRenderStrategy
{
    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        // 默认策略不主动认领厂商，只作 Planner 兜底
        return false;
    }

    @Override
    public ImageReferenceRenderPlan render(ImageReferenceRenderContext ctx)
    {
        String prompt = stripMappingSection(ctx.getOriginalPrompt());

        // 引用类型 URL（按 N 顺序）→ 统一上限截断
        List<ResolvedImageReference> refs = pickReferenceType(ctx.getReferences());
        List<String> urls = new ArrayList<>();
        for (ResolvedImageReference r : refs)
        {
            urls.add(r.getUrl());
        }
        List<String> limited = limitReferenceUrls(urls, ctx, "Default");

        // 占位塌缩为裸「图片N」（与 Sanitizer 一致，不重排编号）
        for (ResolvedImageReference r : ctx.getReferences())
        {
            prompt = replacePlaceholder(prompt, r, "图片" + r.getN());
        }
        // 占位替换后清掉残留 @选择标记（与 Sanitizer 第 3 步对齐）
        prompt = StrUtil.trimToEmpty(stripStrayAtMarkers(prompt));

        log.info("默认参考渲染: refTotal={}, referenceUrls={}", ctx.getReferences().size(), limited.size());
        return ImageReferenceRenderPlan.of(prompt, limited);
    }
}
