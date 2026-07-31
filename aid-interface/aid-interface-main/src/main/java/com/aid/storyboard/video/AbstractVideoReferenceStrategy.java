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

/**
 * 分镜视频参考装配策略抽象基类，收敛参考图截断、首帧挑选、参考图说明拼接、用户补充追加等复用工具方法。
 *
 * @author 视觉AID
 */
public abstract class AbstractVideoReferenceStrategy implements VideoReferenceStrategy
{
    /** 提示词「参考图说明」段前缀。 */
    protected static final String REFERENCE_LEGEND_PREFIX = "参考图说明：";

    /** 业务私有图片占位；策略裁剪引用后必须同步改写提示词，禁止留下悬空序号。 */
    private static final Pattern AT_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /**
     * 截断后的参考素材列表（按 N 升序，仅含 URL 非空，截断到上限），保留富信息用于拼说明。
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
            if (out.size() >= max)
            {
                break;
            }
            if (r != null && StrUtil.isNotBlank(r.getUrl()))
            {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * 拼装「参考图说明」段（图片1=名称（类型）...），编号从 1 递增并与 referenceImages 下标对齐。
     *
     * @param refs 已截断、按 N 升序的参考素材
     * @return 参考图说明段；refs 为空返回空串
     */
    protected String buildReferenceLegend(List<ResolvedReference> refs)
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
            sb.append("图片").append(i + 1).append('=')
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
     * 按最终实际下发的引用列表重排提示词：保留项连续编号，被裁剪项退回资产名称文字。
     */
    protected String remapPromptForPicked(String prompt, List<ResolvedReference> picked)
    {
        Map<Integer, Integer> compactByOriginal = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(picked))
        {
            for (int i = 0; i < picked.size(); i++)
            {
                compactByOriginal.put(picked.get(i).getOriginalN(), i + 1);
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
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            return ctx.getBaseImageUrl();
        }
        ResolvedReference reference = pickSingleReference(ctx);
        return Objects.isNull(reference) ? null : reference.getUrl();
    }
}
