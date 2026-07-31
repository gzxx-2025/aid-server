package com.aid.media.reference.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.reference.ImageReferenceRenderContext;
import com.aid.media.reference.ImageReferenceRenderStrategy;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.RefType;
import com.aid.rps.resolver.StoryboardImageReferenceResolver.ResolvedImageReference;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 图片参考渲染策略抽象基类：收口各厂商策略的共享逻辑（类型中文映射、引用/描述拆分、
 * 占位 token 替换、参考图映射段剥离）。
 *
 * @author 视觉AID
 */
public abstract class AbstractImageReferenceRenderStrategy implements ImageReferenceRenderStrategy
{
    /** asset_type 枚举值常量（与 aid_role_prop_scene.asset_type 一致）。 */
    protected static final String KIND_CHARACTER = "character";
    protected static final String KIND_SCENE = "scene";
    protected static final String KIND_PROP = "prop";

    /** 类型词：外部图 / 未知类型兜底。 */
    protected static final String KIND_LABEL_GENERIC = "参考图";

    /**
     * {@code ---参考图映射---} 段正则：从该 header 起删到结尾（业务层兜底追加的 URL 明文段，
     * 渲染前先剥掉，避免明文 URL 进入渲染后的 prompt）。与 {@code ReferencePromptSanitizer} 口径一致。
     */
    private static final Pattern MAPPING_SECTION = Pattern.compile("\\n?-{2,}\\s*参考图映射\\s*-{2,}[\\s\\S]*$");

    /**
     * 残留 {@code @选择标记} 正则（{@code @} 紧跟非空白）：与 {@code ReferencePromptSanitizer.STRAY_AT_MARKER} 一致。
     * 在占位已替换后执行，剩余 {@code @} 必为视觉导演/画师的选择标记（@中近景/@平视/@50mm标准/@顺光…），
     * 对图像模型是噪声，去 {@code @} 保留枚举值文本。
     */
    private static final Pattern STRAY_AT_MARKER = Pattern.compile("@(?=\\S)");

    /**
     * asset_type → 中文类型词。未知 / null → 「参考图」。
     */
    protected String mapAssetKind(String assetKind)
    {
        if (StrUtil.isBlank(assetKind))
        {
            return KIND_LABEL_GENERIC;
        }
        switch (assetKind)
        {
            case KIND_CHARACTER:
                return "人物";
            case KIND_SCENE:
                return "场景";
            case KIND_PROP:
                return "道具";
            default:
                return KIND_LABEL_GENERIC;
        }
    }

    /**
     * 显示名：优先主资产名（assetName），缺失回落占位名（name）。
     */
    protected String displayName(ResolvedImageReference ref)
    {
        return StrUtil.isNotBlank(ref.getAssetName()) ? StrUtil.trim(ref.getAssetName()) : StrUtil.trim(ref.getName());
    }

    /**
     * 实体标签：{@code 名称（类型）}，如「诸葛亮（人物）」。
     */
    protected String entityLabel(ResolvedImageReference ref)
    {
        return displayName(ref) + "（" + mapAssetKind(ref.getAssetKind()) + "）";
    }

    /**
     * 剥离 prompt 尾部的 {@code ---参考图映射---} 段（若有），返回纯正文。
     */
    protected String stripMappingSection(String prompt)
    {
        if (StrUtil.isBlank(prompt))
        {
            return StrUtil.nullToEmpty(prompt);
        }
        return MAPPING_SECTION.matcher(prompt).replaceAll("");
    }

    /**
     * 把 prompt 里某个引用的 {@code @图片N[...]} 占位整体替换为指定文本。
     * 按 N 匹配（方括号内任意内容）：resolveRich 对同一 N 做了 putIfAbsent，每个 N 仅出现一次，
     * 按 N 匹配比"按 N+name 精确匹配"更鲁棒——避免占位 name 含首尾空格 / 与 manifest 内 trim 后名字不一致时漏替换泄漏。
     * 与 {@code ReferencePromptSanitizer} 的 {@code @图片N\[[^\]]*\]} 口径一致。
     */
    protected String replacePlaceholder(String prompt, ResolvedImageReference ref, String replacement)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        // @图片{N}[任意非右括号内容]：仅按 N 匹配
        String tokenRegex = "@图片" + ref.getN() + "\\[[^\\]]*\\]";
        return prompt.replaceAll(tokenRegex, Matcher.quoteReplacement(replacement));
    }

    /**
     * 去掉残留的 {@code @选择标记}（在所有 {@code @图片N[...]} 占位已替换后调用）。
     */
    protected String stripStrayAtMarkers(String prompt)
    {
        if (StrUtil.isBlank(prompt))
        {
            return StrUtil.nullToEmpty(prompt);
        }
        return STRAY_AT_MARKER.matcher(prompt).replaceAll("");
    }

    /**
     * 为整批引用构建去歧义的实体标签 {@code Map<N, "名称（类型）">}。
     * 默认显示名取主资产名（assetName）；当 ≥2 个引用的「名称（类型）」基础标签发生碰撞
     * （如同一角色的两个形态 诸葛亮_初始形象 / 诸葛亮_雾中借箭 都→"诸葛亮（人物）"）时，
     * 碰撞组回落 form_image.name（带形态后缀）以可区分，避免说明段出现两个相同标签。
     */
    protected Map<Integer, String> buildEntityLabels(List<ResolvedImageReference> refs)
    {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (refs == null || refs.isEmpty())
        {
            return out;
        }
        // 统计基础标签出现次数（显示名 + 类型）
        Map<String, Integer> baseCount = new HashMap<>();
        for (ResolvedImageReference r : refs)
        {
            baseCount.merge(baseLabelKey(r), 1, Integer::sum);
        }
        for (ResolvedImageReference r : refs)
        {
            String kind = mapAssetKind(r.getAssetKind());
            String disp = baseCount.getOrDefault(baseLabelKey(r), 0) > 1
                    ? StrUtil.trim(StrUtil.nullToEmpty(r.getName()))   // 碰撞 → 用更具体的 form_image.name
                    : displayName(r);
            out.put(r.getN(), disp + "（" + kind + "）");
        }
        return out;
    }

    /** 基础标签去重键：显示名 + 类型词。 */
    private String baseLabelKey(ResolvedImageReference r)
    {
        return displayName(r) + "|" + mapAssetKind(r.getAssetKind());
    }

    /**
     * 按模型配置上限截断「引用类型」URL 列表（复用统一治理器）。
     */
    protected List<String> limitReferenceUrls(List<String> urls, ImageReferenceRenderContext ctx, String providerTag)
    {
        return ReferenceImageLimiter.limit(urls, ctx.getModelConfig(), ctx.getFallbackMaxReferenceImages(), providerTag);
    }

    /**
     * 从富化清单中筛出「引用类型且 url 非空」的引用（保持入参顺序）。
     */
    protected List<ResolvedImageReference> pickReferenceType(List<ResolvedImageReference> refs)
    {
        List<ResolvedImageReference> result = new ArrayList<>();
        if (CollectionUtil.isEmpty(refs))
        {
            return result;
        }
        for (ResolvedImageReference r : refs)
        {
            if (r.getType() == RefType.REFERENCE && StrUtil.isNotBlank(r.getUrl()))
            {
                result.add(r);
            }
        }
        return result;
    }
}
