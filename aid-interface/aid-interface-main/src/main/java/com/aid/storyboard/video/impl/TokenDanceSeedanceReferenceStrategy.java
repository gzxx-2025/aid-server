package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import java.util.List;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 保持 TokenDance Seedance 分镜参考图与首帧的独立语义。 */
@Slf4j
@Component
public class TokenDanceSeedanceReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean supportsModelConfig(AiModelConfigVo model)
    {
        return model != null && TokenDanceProtocols.isTokenDance(model.getProviderCode())
                && TokenDanceProtocols.matches(TokenDanceProtocols.SEEDANCE_GENERATIONS, model.getProtocol());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context)
    {
        String capabilityCode = StrUtil.blankToDefault(
                context.getModelConfig().getCapabilityCode(), "").trim().toLowerCase();
        if ("image_to_video".equals(capabilityCode))
        {
            String firstFrame = pickSingleFrame(context);
            String prompt = composePrompt(context.getVideoPrompt(), "", context.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), firstFrame);
        }
        var references = takeRefs(context.getReferences(), context.getMaxReferenceImages());
        if (references.size() > 1 && !supportsMultiImage(context.getModelConfig()))
        {
            log.info("Seedance 参考装配不支持多图: modelCode={}, count={}",
                    context.getModelConfig().getModelCode(), references.size());
            throw new ServiceException("模型不支持多图");
        }
        var urls = references.stream().map(reference -> reference.getUrl()).toList();
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), references),
                buildReferenceLegend(references), context.getUserInputText());
        // 即使只有一张角色参考图也不能提升为首帧，否则固定画幅会落入首帧限制。
        String firstFrame = urls.isEmpty() ? StrUtil.blankToDefault(context.getBaseImageUrl(), null) : null;
        return VideoReferencePlan.of(prompt, urls, firstFrame);
    }
}
