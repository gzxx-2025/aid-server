package com.aid.storyboard.video.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aid.media.constants.VolcengineConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 火山方舟 Seedance 视频参考装配策略：支持多模态参考，按「图片N」序号对齐 content 中的 image_url。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class VolcengineVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return providerCode != null
                && VolcengineConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), ctx.getMaxReferenceImages());

        if (!supportsMultiImage(ctx.getModelConfig()) && picked.size() > 1)
        {
            log.warn("Seedance 参考装配: 模型 {} 不支持多图参考，{} 张裁剪为 1 张",
                    ctx.getModelConfig() == null ? null : ctx.getModelConfig().getModelCode(),
                    picked.size());
            picked = picked.subList(0, 1);
        }

        List<String> urls = new java.util.ArrayList<>();
        for (ResolvedReference r : picked)
        {
            urls.add(r.getUrl());
        }

        String legend = buildReferenceLegend(picked);

        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                legend, ctx.getUserInputText());

        //    - 有多模态参考图时：Provider 只消费 referenceImages（role=reference_image），imageUrl 会被忽略，
        //      故首帧置 null 避免无效字段（角色锁定已由参考图覆盖）。
        //    - 无参考图时：回落首帧垫图 baseImageUrl 走纯首帧 i2v。
        String firstFrame;
        if (CollectionUtil.isEmpty(urls))
        {
            firstFrame = StrUtil.isNotBlank(ctx.getBaseImageUrl()) ? ctx.getBaseImageUrl() : null;
            log.info("Seedance 参考装配: 本镜无可用参考图，按纯文/首帧处理, firstFrame={}", firstFrame != null);
        }
        else
        {
            firstFrame = null;
        }
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }
}
