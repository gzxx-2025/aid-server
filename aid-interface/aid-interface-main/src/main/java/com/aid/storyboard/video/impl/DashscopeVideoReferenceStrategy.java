package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aid.common.exception.ServiceException;
import com.aid.media.constants.DashscopeConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.collection.CollectionUtil;
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
                && DashscopeConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        // HappyHorse 参考生视频：消费多图参考（input.media[reference_image]），保留「图片N」标号（Provider 转 [Image N]）。
        String modelCode = ctx.getModelConfig() == null ? null : ctx.getModelConfig().getModelCode();
        if (modelCode != null
                && modelCode.toLowerCase().startsWith(DashscopeConstants.MODEL_HAPPYHORSE_PREFIX))
        {
            return assembleHappyHorseMultiImage(ctx);
        }

        // 其余百炼模型（万相 img_url / 可灵 / 爱诗 media）当前仅消费单首帧 imageUrl，
        // 尚未消费 options.referenceImages 的多模态参考。为避免装配出 Provider 会静默丢弃的多图方案
        // （给业务"已传多参考图"的假象），这里统一退化为「单首帧 + 不插标号」。
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
            log.info("百炼视频参考装配: 当前 Provider 仅消费单首帧，本镜 {} 个引用素材收敛为 1 张首帧, modelCode={}",
                    ctx.getReferences().size(), modelCode);
        }
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame);
    }

    /**
     * HappyHorse 多图参考装配：按 N 升序截断取参考图（media 必填，空则 fail-fast），将正文 {@code @图片N} 重编号为连续序号
     * 并追加参考图说明段，被截断的引用回退为资产名；参考生视频无首帧概念，firstFrame 置 null。
     */
    private VideoReferencePlan assembleHappyHorseMultiImage(VideoReferenceContext ctx)
    {
        //    钳到 9 是关键：避免运营误配 maxReferenceImages=-1(无限)/>9 时，策略层按更大上限重编号出
        //    [Image 10] 之类引用，而 Provider 实际只发 ≤9 张 media，导致正文与 media 再次错位。
        int effectiveMax = Math.min(ctx.getMaxReferenceImages(), DashscopeConstants.HAPPYHORSE_MAX_REFERENCE_IMAGES);
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), effectiveMax);
        if (CollectionUtil.isEmpty(picked))
        {
            log.error("HappyHorse 参考装配: 无可用参考图，参考生视频要求至少1张, modelCode={}",
                    ctx.getModelConfig() == null ? null : ctx.getModelConfig().getModelCode());
            throw new ServiceException("缺少参考图");
        }
        Map<Integer, Integer> compactByOriginal = new HashMap<>();
        for (int i = 0; i < picked.size(); i++)
        {
            compactByOriginal.put(picked.get(i).getOriginalN(), i + 1);
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
        List<String> urls = new ArrayList<>();
        for (ResolvedReference r : picked)
        {
            urls.add(r.getUrl());
        }
        String legend = buildReferenceLegend(picked);
        String finalPrompt = composePrompt(remappedPrompt, legend, ctx.getUserInputText());
        log.info("HappyHorse 参考装配: 多图参考 {} 张, 重编号 {}, 截断回退 {}",
                urls.size(), compactByOriginal, droppedNameByN.keySet());
        return VideoReferencePlan.of(finalPrompt, urls, null);
    }

    /** {@code @图片N[name]} 占位正则（与解析器对齐）。 */
    private static final Pattern AT_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /**
     * 重写正文里的 {@code @图片N[name]}：命中的按紧凑序号重编号，被截断的回退为资产名文本，非法序号原样保留。
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
