package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.common.exception.ServiceException;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** MiniMax H3 五个平台场景的分镜参考素材装配。 */
@Slf4j
@Component
public class MinimaxH3VideoReferenceStrategy extends AbstractVideoReferenceStrategy {

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return MinimaxH3Constants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context) {
        AiModelConfigVo config = context.getModelConfig();
        String code = resolveScenario(config);
        if (MinimaxH3Constants.MODEL_REFERENCE.equals(code)) {
            return referencePlan(context);
        }
        if (MinimaxH3Constants.MODEL_T2V.equals(code)) {
            String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), List.of()),
                null, context.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), null);
        }
        if (MinimaxH3Constants.MODEL_I2V_LAST.equals(code)) {
            return lastFramePlan(context);
        }
        if (MinimaxH3Constants.MODEL_I2V_FIRST_LAST.equals(code)) {
            return firstLastFramePlan(context);
        }
        if (MinimaxH3Constants.MODEL_I2V_FIRST.equals(code)) {
            return firstFramePlan(context);
        }
        throw rejected("unknown platform model code=" + code, "模型场景配置无效");
    }

    /** 统一模型优先按所选 capability overlay 的官方场景分派，旧模型编码仅作兼容回退。 */
    private String resolveScenario(AiModelConfigVo config) {
        if (config != null && StrUtil.isNotBlank(config.getCapabilityJson())) {
            try {
                JSONObject capability = JSONUtil.parseObj(config.getCapabilityJson());
                String scenario = capability.getStr("videoScenario");
                if (StrUtil.isNotBlank(scenario)) {
                    return switch (scenario.trim().toLowerCase(Locale.ROOT)) {
                        case "text_to_video", "text" -> MinimaxH3Constants.MODEL_T2V;
                        case "first_frame", "image_to_video" -> MinimaxH3Constants.MODEL_I2V_FIRST;
                        case "last_frame", "last_frame_to_video" -> MinimaxH3Constants.MODEL_I2V_LAST;
                        case "first_last_frame", "start_end_to_video" -> MinimaxH3Constants.MODEL_I2V_FIRST_LAST;
                        case "multimodal_reference", "reference_to_video", "reference" -> MinimaxH3Constants.MODEL_REFERENCE;
                        default -> throw rejected("unknown configured videoScenario=" + scenario,
                                "模型场景配置无效");
                    };
                }
            } catch (ServiceException ex) {
                throw ex;
            } catch (Exception ex) {
                throw rejected("invalid capabilityJson: " + ex.getClass().getSimpleName(), "模型场景配置无效");
            }
        }
        return config == null ? null : config.getModelCode();
    }

    private VideoReferencePlan firstFramePlan(VideoReferenceContext context) {
        ResolvedReference picked = pickSingleReference(context);
        List<ResolvedReference> pickedRefs = picked == null ? List.of() : List.of(picked);
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), pickedRefs),
            buildReferenceLegend(pickedRefs), context.getUserInputText());
        return VideoReferencePlan.of(prompt, List.of(), pickSingleFrame(context));
    }

    private VideoReferencePlan lastFramePlan(VideoReferenceContext context) {
        ResolvedReference picked = pickSingleReference(context);
        List<ResolvedReference> pickedRefs = picked == null ? List.of() : List.of(picked);
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), pickedRefs),
            buildReferenceLegend(pickedRefs), context.getUserInputText());
        String lastFrame = pickSingleFrame(context);
        Map<String, Object> options = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(lastFrame)) {
            options.put("lastFrameImageUrl", lastFrame);
        }
        return VideoReferencePlan.of(prompt, List.of(), null, options);
    }

    private VideoReferencePlan firstLastFramePlan(VideoReferenceContext context) {
        List<ResolvedReference> available = takeRefs(context.getReferences(), 2);
        String firstFrame;
        String lastFrame;
        List<ResolvedReference> picked;
        if (StrUtil.isNotBlank(context.getBaseImageUrl())) {
            firstFrame = context.getBaseImageUrl();
            if (StrUtil.isNotBlank(context.getLastFrameImageUrl())) {
                if (!available.isEmpty()) {
                    throw rejected("first-last-frame scene has explicit tail and extra references",
                        "首尾帧图片数量超限");
                }
                lastFrame = context.getLastFrameImageUrl();
                picked = List.of();
            } else if (available.isEmpty()) {
                throw rejected("first-last-frame scene has base image but no tail reference",
                    "请提供尾帧图片");
            } else {
                lastFrame = available.get(0).getUrl();
                picked = List.of(available.get(0));
            }
        } else if (StrUtil.isNotBlank(context.getLastFrameImageUrl())) {
            if (available.size() != 1) {
                throw rejected("first-last-frame scene with explicit tail requires one first reference, count="
                    + available.size(), "请提供首尾帧图片");
            }
            firstFrame = available.get(0).getUrl();
            lastFrame = context.getLastFrameImageUrl();
            picked = List.of(available.get(0));
        } else {
            if (available.size() < 2) {
                throw rejected("first-last-frame scene without base image has fewer than two references, count="
                    + available.size(), "请提供首尾帧图片");
            }
            firstFrame = available.get(0).getUrl();
            lastFrame = available.get(1).getUrl();
            picked = List.of(available.get(0), available.get(1));
        }
        if (firstFrame.equals(lastFrame)) {
            throw rejected("first and last frame URLs are identical", "首尾帧图片不能相同");
        }
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), picked),
            buildReferenceLegend(picked), context.getUserInputText());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("lastFrameImageUrl", lastFrame);
        return VideoReferencePlan.of(prompt, List.of(), firstFrame, options);
    }

    private VideoReferencePlan referencePlan(VideoReferenceContext context) {
        int max = context.getMaxReferenceImages() > 0 ? context.getMaxReferenceImages() : 9;
        List<String> urls = new ArrayList<>();
        if (StrUtil.isNotBlank(context.getBaseImageUrl())) {
            urls.add(context.getBaseImageUrl());
        }
        List<ResolvedReference> picked = takeRefs(context.getReferences(), Math.max(0, max - urls.size()));
        for (ResolvedReference reference : picked) {
            if (!urls.contains(reference.getUrl())) {
                urls.add(reference.getUrl());
            }
        }
        if (urls.size() > max) {
            throw rejected("reference images exceed configured limit", "参考图片数量超限");
        }
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), picked),
            buildReferenceLegend(picked), context.getUserInputText());
        Map<String, Object> options = new LinkedHashMap<>();
        if (!urls.isEmpty()) {
            options.put("referenceImages", urls);
        }
        return VideoReferencePlan.of(prompt, urls, null, options);
    }

    private static ServiceException rejected(String reason, String clientMessage) {
        log.warn("MiniMax H3 storyboard reference rejected: {}", reason);
        return new ServiceException(clientMessage);
    }
}
