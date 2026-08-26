package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

/** 可配置异步视频协议的多参考图装配策略。 */
@Component
public class ConfigurableAsyncVideoReferenceStrategy extends AbstractVideoReferenceStrategy {

    @Override
    public boolean supportsModelConfig(AiModelConfigVo modelConfig) {
        return modelConfig != null
                && ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO.equalsIgnoreCase(
                        StrUtil.trim(modelConfig.getProtocol()));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context) {
        int max = Math.max(0, context.getMaxReferenceImages());
        if (max == 0) {
            String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), List.of()),
                    null, context.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), null);
        }
        List<String> urls = new ArrayList<>();
        boolean hasBase = StrUtil.isNotBlank(context.getBaseImageUrl());
        if (hasBase) {
            urls.add(context.getBaseImageUrl());
        }
        List<ResolvedReference> picked = new ArrayList<>();
        int remaining = Math.max(0, max - urls.size());
        for (ResolvedReference reference : context.getReferences()) {
            if (picked.size() >= remaining) {
                break;
            }
            if (reference == null || StrUtil.isBlank(reference.getUrl())
                    || urls.contains(reference.getUrl())) {
                continue;
            }
            picked.add(reference);
            urls.add(reference.getUrl());
        }
        int offset = hasBase ? 1 : 0;
        String legend = buildReferenceLegend(picked, offset);
        if (hasBase) {
            String suffix = StrUtil.isBlank(legend) ? ""
                    : "，" + legend.substring(REFERENCE_LEGEND_PREFIX.length());
            legend = REFERENCE_LEGEND_PREFIX + "图片1=基础参考" + suffix;
        }
        String prompt = composePrompt(
                remapPromptForPicked(context.getVideoPrompt(), picked, offset),
                legend, context.getUserInputText());
        Map<String, Object> options = new LinkedHashMap<>();
        if (!urls.isEmpty()) {
            options.put("referenceImages", urls);
        }
        return VideoReferencePlan.of(prompt, urls, null, options);
    }
}
