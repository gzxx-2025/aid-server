package com.aid.storyboard.video.impl;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aid.media.constants.JimengConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 即梦 CV 视频参考装配策略：仅支持单首帧图，多图参考收敛为一张首帧，不拼参考图标号。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class JimengVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return providerCode != null
                && JimengConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        String firstFrame = pickSingleFrame(ctx);

        ResolvedReference picked = pickSingleReference(ctx);
        java.util.List<ResolvedReference> pickedRefs = picked == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(picked);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), pickedRefs),
                null, ctx.getUserInputText());

        List<String> urls = StrUtil.isNotBlank(firstFrame)
                ? Collections.singletonList(firstFrame)
                : Collections.emptyList();

        if (ctx.getReferences().size() > 1)
        {
            log.info("即梦视频参考装配: 仅支持单首帧，本镜 {} 个引用素材已收敛为 1 张首帧",
                    ctx.getReferences().size());
        }
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }
}
