package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aid.media.constants.AgnesConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Agnes 视频参考装配策略——多图视频生成。
 *
 * @author 视觉AID
 */
@Slf4j
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
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), ctx.getMaxReferenceImages());

        List<String> urls = new ArrayList<>();
        for (ResolvedReference r : picked)
        {
            urls.add(r.getUrl());
        }

        String legend = buildReferenceLegend(picked);

        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                legend, ctx.getUserInputText());

        //    - 有参考图：Provider 走 extra_body.image，首帧置 null 避免与多图参考冲突；
        //    - 无参考图：回落 baseImageUrl 走纯首帧 i2v / 文生视频。
        String firstFrame;
        if (CollectionUtil.isEmpty(urls))
        {
            firstFrame = StrUtil.isNotBlank(ctx.getBaseImageUrl()) ? ctx.getBaseImageUrl() : null;
            log.info("Agnes 视频参考装配: 本镜无可用参考图，按纯文/首帧处理, firstFrame={}", firstFrame != null);
        }
        else
        {
            firstFrame = null;
            log.info("Agnes 视频参考装配: 多图视频, refCount={}", urls.size());
        }
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }
}
