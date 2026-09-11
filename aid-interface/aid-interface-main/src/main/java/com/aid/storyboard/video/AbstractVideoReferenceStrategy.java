package com.aid.storyboard.video;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜视频参考装配策略的公共校验与提示词装配工具。
 *
 * @author 视觉AID
 */
@Slf4j
public abstract class AbstractVideoReferenceStrategy implements VideoReferenceStrategy
{
    /** 提示词「参考图说明」段前缀。 */
    protected static final String REFERENCE_LEGEND_PREFIX = "参考图说明：";

    /** 业务私有图片占位；能力校验后按实际素材重编号，禁止留下悬空序号。 */
    private static final Pattern AT_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /**
     * 按原顺序收集参考素材，超出上限拒绝。
     */
    protected List<ResolvedReference> takeRefs(List<ResolvedReference> refs, int max)
    {
        List<ResolvedReference> out = new ArrayList<>();
        if (CollectionUtil.isEmpty(refs))
        {
            return out;
        }
        for (ResolvedReference r : refs)
        {
            if (r != null && StrUtil.isNotBlank(r.getUrl()))
            {
                out.add(r);
            }
        }
        if (max >= 0 && out.size() > max) {
            log.info("分镜参考图片数量超限: max={}, actual={}", max, out.size());
            throw new ServiceException("参考图片数量超限");
        }
        return out;
    }

    /**
     * 拼装「参考图说明」段（图片1=名称（类型）...），编号从 1 递增并与 referenceImages 下标对齐。
     *
     * @param refs 已校验、按 N 升序的参考素材
     * @return 参考图说明段；refs 为空返回空串
     */
    protected String buildReferenceLegend(List<ResolvedReference> refs)
    {
        return buildReferenceLegend(refs, 0);
    }

    /** 按实际数组前置数量生成连续编号的参考图说明。 */
    protected String buildReferenceLegend(List<ResolvedReference> refs, int indexOffset)
    {
        if (CollectionUtil.isEmpty(refs))
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(REFERENCE_LEGEND_PREFIX);
        for (int i = 0; i < refs.size(); i++)
        {
            ResolvedReference r = refs.get(i);
            if (i > 0)
            {
                sb.append("，");
            }
            sb.append("图片").append(indexOffset + i + 1).append('=')
                    .append(r.displayName()).append('（').append(r.typeLabel()).append('）');
        }
        return sb.toString();
    }

    /**
     * 在提示词正文后追加「参考图说明」与用户补充文本。
     *
     * @param videoPrompt   视频提示词正文
     * @param legend        参考图说明段（可空）
     * @param userInputText 用户补充文本（可空）
     * @return 最终 prompt
     */
    protected String composePrompt(String videoPrompt, String legend, String userInputText)
    {
        StringBuilder sb = new StringBuilder(StrUtil.nullToEmpty(videoPrompt));
        if (StrUtil.isNotBlank(legend))
        {
            sb.append('\n').append(legend);
        }
        if (StrUtil.isNotBlank(userInputText))
        {
            sb.append('\n').append("用户补充：").append(userInputText);
        }
        return sb.toString();
    }

    /**
     * 按最终实际下发的引用列表重排提示词：保留项连续编号，非引用型素材退回资产名称文字。
     */
    protected String remapPromptForPicked(String prompt, List<ResolvedReference> picked)
    {
        return remapPromptForPicked(prompt, picked, 0);
    }

    /** 按最终数组前置数量重排私有引用编号。 */
    protected String remapPromptForPicked(String prompt, List<ResolvedReference> picked, int indexOffset)
    {
        Map<Integer, Integer> compactByOriginal = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(picked))
        {
            for (int i = 0; i < picked.size(); i++)
            {
                compactByOriginal.put(picked.get(i).getOriginalN(), indexOffset + i + 1);
            }
        }
        Matcher matcher = AT_REF_PATTERN.matcher(StrUtil.nullToEmpty(prompt));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find())
        {
            int originalN;
            try { originalN = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group())); continue; }
            Integer compactN = compactByOriginal.get(originalN);
            String name = StrUtil.trimToEmpty(matcher.group(2));
            String replacement = Objects.nonNull(compactN)
                    ? "@图片" + compactN + "[" + name + "]"
                    : name;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /** 单图策略实际采用的参考对象；显式首帧存在时返回 null，表示所有私有引用均降级为文字。 */
    protected ResolvedReference pickSingleReference(VideoReferenceContext ctx)
    {
        validateSingleFrameInputs(ctx);
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()) || CollectionUtil.isEmpty(ctx.getReferences()))
        {
            return null;
        }
        for (ResolvedReference reference : ctx.getReferences())
        {
            if (reference != null && reference.isCharacter() && StrUtil.isNotBlank(reference.getUrl()))
            {
                return reference;
            }
        }
        for (ResolvedReference reference : ctx.getReferences())
        {
            if (reference != null && StrUtil.isNotBlank(reference.getUrl()))
            {
                return reference;
            }
        }
        return null;
    }

    /**
     * 单图厂商挑首帧：显式垫图 baseImageUrl 优先，其次首个角色类参考图，再次首张参考图。
     *
     * @return 首帧 URL；无任何素材时返回 null
     */
    protected String pickSingleFrame(VideoReferenceContext ctx)
    {
        validateSingleFrameInputs(ctx);
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            return ctx.getBaseImageUrl();
        }
        ResolvedReference reference = pickSingleReference(ctx);
        return Objects.isNull(reference) ? null : reference.getUrl();
    }

    private void validateSingleFrameInputs(VideoReferenceContext ctx) {
        Map<String, Boolean> images = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl())) images.put(ctx.getBaseImageUrl().trim(), true);
        if (ctx.getReferences() != null) {
            for (ResolvedReference reference : ctx.getReferences()) {
                if (reference != null && StrUtil.isNotBlank(reference.getUrl())) {
                    images.put(reference.getUrl().trim(), true);
                }
            }
        }
        if (images.size() > 1) {
            log.info("单图场景参考图片数量超限: actual={}", images.size());
            throw new ServiceException("参考图片数量超限");
        }
    }
}
