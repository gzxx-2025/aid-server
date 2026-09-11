package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.media.constants.AgnesConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.util.StrUtil;

/**
 * Agnes 视频参考装配策略——多图视频生成。
 *
 * @author 视觉AID
 */
@Component
public class AgnesVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        // Agnes 视频：按 provider_code 精确归属
        return providerCode != null
                && AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        String scene = resolveScene(ctx);
        return switch (scene)
        {
            case "text_to_video" -> assembleText(ctx);
            case "image_to_video" -> assembleFirstFrame(ctx);
            case "start_end_to_video" -> assembleStartEnd(ctx);
            case "reference_to_video" -> assembleReference(ctx);
            default -> throw new com.aid.common.exception.ServiceException("模型场景配置无效");
        };
    }

    private VideoReferencePlan assembleText(VideoReferenceContext ctx)
    {
        String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), List.of()),
                null, ctx.getUserInputText());
        return VideoReferencePlan.of(prompt, List.of(), null);
    }

    private VideoReferencePlan assembleFirstFrame(VideoReferenceContext ctx)
    {
        ResolvedReference picked = StrUtil.isNotBlank(ctx.getBaseImageUrl())
                ? null : pickSingleReference(ctx);
        String firstFrame = StrUtil.isNotBlank(ctx.getBaseImageUrl())
                ? ctx.getBaseImageUrl() : picked == null ? null : picked.getUrl();
        List<ResolvedReference> promptRefs = picked == null ? List.of() : List.of(picked);
        String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), promptRefs),
                buildReferenceLegend(promptRefs), ctx.getUserInputText());
        return VideoReferencePlan.of(prompt, List.of(), firstFrame);
    }

    private VideoReferencePlan assembleStartEnd(VideoReferenceContext ctx)
    {
        List<ResolvedReference> available = validReferences(ctx);
        int requestedCount = available.size()
                + (StrUtil.isNotBlank(ctx.getBaseImageUrl()) ? 1 : 0)
                + (StrUtil.isNotBlank(ctx.getLastFrameImageUrl()) ? 1 : 0);
        if (requestedCount > 2)
        {
            throw new com.aid.common.exception.ServiceException("首尾帧图片数量超限");
        }
        String firstFrame;
        String lastFrame;
        List<ResolvedReference> picked;
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            if (StrUtil.isNotBlank(ctx.getLastFrameImageUrl()))
            {
                firstFrame = ctx.getBaseImageUrl();
                lastFrame = ctx.getLastFrameImageUrl();
                picked = List.of();
            }
            else if (available.isEmpty())
            {
                throw new com.aid.common.exception.ServiceException("请提供尾帧图片");
            }
            else
            {
                firstFrame = ctx.getBaseImageUrl();
                lastFrame = available.get(0).getUrl();
                picked = List.of(available.get(0));
            }
        }
        else if (StrUtil.isNotBlank(ctx.getLastFrameImageUrl()))
        {
            if (available.size() != 1)
            {
                throw new com.aid.common.exception.ServiceException("请提供首尾帧图片");
            }
            firstFrame = available.get(0).getUrl();
            lastFrame = ctx.getLastFrameImageUrl();
            picked = List.of(available.get(0));
        }
        else
        {
            if (available.size() < 2)
            {
                throw new com.aid.common.exception.ServiceException("请提供首尾帧图片");
            }
            firstFrame = available.get(0).getUrl();
            lastFrame = available.get(1).getUrl();
            picked = List.of(available.get(0), available.get(1));
        }
        if (firstFrame.equals(lastFrame))
        {
            throw new com.aid.common.exception.ServiceException("首尾帧图片不能相同");
        }
        String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                buildReferenceLegend(picked), ctx.getUserInputText());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("lastFrameImageUrl", lastFrame);
        return VideoReferencePlan.of(prompt, List.of(), firstFrame, options);
    }

    private VideoReferencePlan assembleReference(VideoReferenceContext ctx)
    {
        int max = ctx.getMaxReferenceImages();
        List<ResolvedReference> available = validReferences(ctx);
        int requestedCount = available.size() + (StrUtil.isNotBlank(ctx.getBaseImageUrl()) ? 1 : 0);
        if (max >= 0 && requestedCount > max)
        {
            throw new com.aid.common.exception.ServiceException("参考图片数量超限");
        }
        List<String> urls = new ArrayList<>();
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            urls.add(ctx.getBaseImageUrl());
        }
        int remaining = max < 0 ? -1 : Math.max(0, max - urls.size());
        List<ResolvedReference> picked = takeRefs(available, remaining);
        for (ResolvedReference reference : picked)
        {
            if (!urls.contains(reference.getUrl()))
            {
                urls.add(reference.getUrl());
            }
        }
        if (max >= 0 && urls.size() > max)
        {
            throw new com.aid.common.exception.ServiceException("参考图片数量超限");
        }
        String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                buildReferenceLegend(picked), ctx.getUserInputText());
        return VideoReferencePlan.of(prompt, urls, null);
    }

    private List<ResolvedReference> validReferences(VideoReferenceContext ctx)
    {
        List<ResolvedReference> result = new ArrayList<>();
        for (ResolvedReference reference : ctx.getReferences())
        {
            if (reference != null && StrUtil.isNotBlank(reference.getUrl()))
            {
                result.add(reference);
            }
        }
        return result;
    }

    private String resolveScene(VideoReferenceContext ctx)
    {
        if (ctx.getModelConfig() == null)
        {
            return "image_to_video";
        }
        String scene = StrUtil.blankToDefault(ctx.getModelConfig().getCapabilityCode(),
                ctx.getModelConfig().getGenerateMode());
        if (StrUtil.isBlank(scene))
        {
            return "image_to_video";
        }
        return scene.trim().toLowerCase(Locale.ROOT);
    }

}
