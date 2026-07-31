package com.aid.storyboard.video.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 通用兜底视频参考装配策略（providerCode 未命中专用策略时使用）：保守采用单首帧、不插标号。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class DefaultVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean isDefault()
    {
        return true;
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        String firstFrame = pickSingleFrame(ctx);
        ResolvedReference picked = pickSingleReference(ctx);
        List<ResolvedReference> pickedRefs = picked == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(picked);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), pickedRefs),
                null, ctx.getUserInputText());
        List<String> urls = StrUtil.isNotBlank(firstFrame)
                ? java.util.Collections.singletonList(firstFrame)
                : java.util.Collections.emptyList();
        if (ctx.getReferences().size() > 1)
        {
            log.info("默认视频参考装配: 未知厂商按单首帧安全口径，{} 个引用收敛为 1 张, providerCode={}",
                    ctx.getReferences().size(),
                    ctx.getModelConfig() == null ? null : ctx.getModelConfig().getProviderCode());
        }
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }
}
