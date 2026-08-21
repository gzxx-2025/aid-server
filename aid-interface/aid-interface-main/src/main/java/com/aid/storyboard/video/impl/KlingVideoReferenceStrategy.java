package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按模型场景装配可灵分镜参考素材。 */
@Component
public class KlingVideoReferenceStrategy extends AbstractVideoReferenceStrategy {

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context) {
        String scenario = scenario(context.getModelConfig());
        if (KlingConstants.SCENARIO_OMNI_REFERENCE.equals(scenario)) {
            return assembleReference(context);
        }
        if (KlingConstants.SCENARIO_OMNI_T2V.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)) {
            String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), List.of()),
                null, context.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), null);
        }
        return assembleSingleFrame(context);
    }

    private VideoReferencePlan assembleSingleFrame(VideoReferenceContext context) {
        ResolvedReference picked = pickSingleReference(context);
        List<ResolvedReference> pickedRefs = picked == null ? List.of() : List.of(picked);
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), pickedRefs),
            buildReferenceLegend(pickedRefs), context.getUserInputText());
        String firstFrame = pickSingleFrame(context);
        return VideoReferencePlan.of(prompt, List.of(), firstFrame);
    }

    private VideoReferencePlan assembleReference(VideoReferenceContext context) {
        int firstFrameSlots = StrUtil.isNotBlank(context.getBaseImageUrl()) ? 1 : 0;
        int referenceLimit = Math.max(0, context.getMaxReferenceImages() - firstFrameSlots);
        List<ResolvedReference> picked = takeRefs(context.getReferences(), referenceLimit);
        List<String> urls = new ArrayList<>();
        for (ResolvedReference reference : picked) {
            urls.add(reference.getUrl());
        }
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), picked),
            buildReferenceLegend(picked), context.getUserInputText());
        Map<String, Object> options = new LinkedHashMap<>();
        if (!urls.isEmpty()) {
            options.put("referenceImages", urls);
        }
        return VideoReferencePlan.of(prompt, urls, context.getBaseImageUrl(), options);
    }

    private String scenario(AiModelConfigVo config) {
        if (config == null || StrUtil.isBlank(config.getCapabilityJson())) {
            return "";
        }
        try {
            return JSONUtil.parseObj(config.getCapabilityJson()).getStr("klingScenario", "");
        } catch (Exception ex) {
            return "";
        }
    }

}
