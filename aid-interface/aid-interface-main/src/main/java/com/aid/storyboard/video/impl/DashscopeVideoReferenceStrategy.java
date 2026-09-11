package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里百炼（万相 / 可灵 / 爱诗）视频参考装配策略：HappyHorse 走多图参考，其余方言退化为单首帧、不插标号。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class DashscopeVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return providerCode != null
                && (DashscopeConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim())
                || DashscopeConstants.PROVIDER_CODE_WAN3.equalsIgnoreCase(providerCode.trim()));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        // HappyHorse 参考生视频：消费多图参考（input.media[reference_image]），保留「图片N」标号（Provider 转 [Image N]）。
        String modelCode = ctx.getModelConfig() == null ? null : ctx.getModelConfig().getModelCode();
        if (isWan3(ctx.getModelConfig()))
        {
            return assembleWan3(ctx);
        }
        if (modelCode != null
                && modelCode.toLowerCase().startsWith(DashscopeConstants.MODEL_HAPPYHORSE_PREFIX))
        {
            return assembleHappyHorseMultiImage(ctx);
        }

        // 其余百炼模型（万相 img_url / 可灵 / 爱诗 media）当前仅消费单首帧 imageUrl；
        // 多图输入由公共校验直接拒绝，这里只装配已经合法的单首帧请求。
        String firstFrame = pickSingleFrame(ctx);
        ResolvedReference picked = pickSingleReference(ctx);
        List<ResolvedReference> pickedRefs = picked == null ? java.util.Collections.emptyList()
                : java.util.Collections.singletonList(picked);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), pickedRefs),
                null, ctx.getUserInputText());
        List<String> urls = StrUtil.isNotBlank(firstFrame)
                ? java.util.Collections.singletonList(firstFrame)
                : java.util.Collections.emptyList();
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }

    /** Wan3.0 参考场景最多装配10张图，不再退化为单首帧。 */
    private VideoReferencePlan assembleWan3(VideoReferenceContext ctx)
    {
        String capability = ctx.getModelConfig() == null ? null
                : StrUtil.trim(ctx.getModelConfig().getCapabilityCode());
        if ("text_to_video".equalsIgnoreCase(capability))
        {
            String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), List.of()),
                    null, ctx.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), null);
        }
        if ("image_to_video".equalsIgnoreCase(capability)
                || "start_end_to_video".equalsIgnoreCase(capability))
        {
            String firstFrame = pickSingleFrame(ctx);
            String prompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), List.of()),
                    null, ctx.getUserInputText());
            return VideoReferencePlan.of(prompt, List.of(), firstFrame);
        }
        int max = officialImageLimit(ctx.getMaxReferenceImages(),
                DashscopeConstants.WAN3_MAX_REFERENCE_IMAGES);
        List<String> urls = new ArrayList<>();
        boolean hasBase = StrUtil.isNotBlank(ctx.getBaseImageUrl());
        if (hasBase)
        {
            urls.add(ctx.getBaseImageUrl().trim());
        }
        List<ResolvedReference> picked = takeUniqueRefs(ctx.getReferences(), urls,
                Math.max(0, max - urls.size()));
        int offset = hasBase ? 1 : 0;
        String legend = referenceLegend(picked, offset, hasBase);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked, offset),
                legend, ctx.getUserInputText());
        return VideoReferencePlan.of(finalPrompt, urls, null);
    }

    private boolean isWan3(AiModelConfigVo modelConfig)
    {
        String model = ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        if (StrUtil.isBlank(model))
        {
            return false;
        }
        String normalized = model.trim();
        return DashscopeConstants.MODEL_WAN3.equalsIgnoreCase(normalized)
                || DashscopeConstants.MODEL_WAN3_PRIME.equalsIgnoreCase(normalized);
    }

    /**
     * HappyHorse 多图参考装配：按 N 升序校验并取参考图（media 必填，空则 fail-fast），将正文 {@code @图片N}
     * 重编号为连续序号并追加参考图说明段；参考生视频无首帧概念，firstFrame 置 null。
     */
    private VideoReferencePlan assembleHappyHorseMultiImage(VideoReferenceContext ctx)
    {
        //    钳到 9 是关键：避免运营误配 maxReferenceImages=-1(无限)/>9 时，策略层按更大上限重编号出
        //    [Image 10] 之类引用，而 Provider 实际只发 ≤9 张 media，导致正文与 media 再次错位。
        int effectiveMax = officialImageLimit(ctx.getMaxReferenceImages(),
                DashscopeConstants.HAPPYHORSE_MAX_REFERENCE_IMAGES);
        List<String> urls = new ArrayList<>();
        boolean hasBase = StrUtil.isNotBlank(ctx.getBaseImageUrl());
        if (hasBase)
        {
            urls.add(ctx.getBaseImageUrl().trim());
        }
        List<ResolvedReference> picked = takeUniqueRefs(ctx.getReferences(), urls,
                Math.max(0, effectiveMax - urls.size()));
        if (urls.isEmpty())
        {
            log.error("HappyHorse 参考装配: 无可用参考图，参考生视频要求至少1张, modelCode={}",
                    ctx.getModelConfig() == null ? null : ctx.getModelConfig().getModelCode());
            throw new ServiceException("缺少参考图");
        }
        Map<Integer, Integer> compactByOriginal = new HashMap<>();
        for (int i = 0; i < picked.size(); i++)
        {
            compactByOriginal.put(picked.get(i).getOriginalN(), i + 1 + (hasBase ? 1 : 0));
        }
        Map<Integer, String> droppedNameByN = new HashMap<>();
        for (ResolvedReference r : ctx.getReferences())
        {
            if (!compactByOriginal.containsKey(r.getOriginalN()))
            {
                droppedNameByN.put(r.getOriginalN(), r.displayName());
            }
        }
        String remappedPrompt = remapReferences(ctx.getVideoPrompt(), compactByOriginal, droppedNameByN);
        String legend = referenceLegend(picked, hasBase ? 1 : 0, hasBase);
        String finalPrompt = composePrompt(remappedPrompt, legend, ctx.getUserInputText());
        log.info("HappyHorse 参考装配: 多图参考 {} 张, 重编号 {}, 描述型素材 {}",
                urls.size(), compactByOriginal, droppedNameByN.keySet());
        return VideoReferencePlan.of(finalPrompt, urls, null);
    }

    private List<ResolvedReference> takeUniqueRefs(List<ResolvedReference> references,
                                                    List<String> urls, int remaining)
    {
        List<ResolvedReference> picked = new ArrayList<>();
        if (references == null)
        {
            return picked;
        }
        for (ResolvedReference reference : references)
        {
            if (reference == null || StrUtil.isBlank(reference.getUrl())
                    || urls.contains(reference.getUrl().trim()))
            {
                continue;
            }
            if (picked.size() >= remaining)
            {
                throw new ServiceException("参考图片数量超限");
            }
            picked.add(reference);
            urls.add(reference.getUrl().trim());
        }
        return picked;
    }

    private int officialImageLimit(int configured, int officialMaximum)
    {
        return configured < 0 ? officialMaximum : Math.min(configured, officialMaximum);
    }

    private String referenceLegend(List<ResolvedReference> picked, int offset, boolean hasBase)
    {
        String legend = buildReferenceLegend(picked, offset);
        if (!hasBase)
        {
            return StrUtil.blankToDefault(legend, null);
        }
        String suffix = StrUtil.isBlank(legend) ? ""
                : "；" + legend.substring(REFERENCE_LEGEND_PREFIX.length());
        return REFERENCE_LEGEND_PREFIX + "图片1=基础参考" + suffix;
    }

    /** {@code @图片N[name]} 占位正则（与解析器对齐）。 */
    private static final Pattern AT_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /**
     * 重写正文里的 {@code @图片N[name]}：命中的按紧凑序号重编号，非引用型素材回退为资产名文本，非法序号原样保留。
     */
    private String remapReferences(String prompt, Map<Integer, Integer> compactByOriginal,
                                   Map<Integer, String> droppedNameByN)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        Matcher m = AT_REF_PATTERN.matcher(prompt);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String replacement;
            int n;
            try
            {
                n = Integer.parseInt(m.group(1));
            }
            catch (NumberFormatException e)
            {
                // 非法序号：原样保留
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String name = m.group(2);
            Integer compact = compactByOriginal.get(n);
            if (compact != null)
            {
                replacement = "@图片" + compact + "[" + name + "]";
            }
            else
            {
                String fallback = droppedNameByN.get(n);
                replacement = StrUtil.isNotBlank(fallback) ? fallback : (name == null ? "" : name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
