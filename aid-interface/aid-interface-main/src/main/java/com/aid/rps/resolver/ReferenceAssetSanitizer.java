package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 引用资产清洗器（全局通用、厂商/业务无关、幂等无副作用）。
 *
 * @author 视觉AID
 */
public final class ReferenceAssetSanitizer
{
    private ReferenceAssetSanitizer() {}

    /** 引用名提取正则：匹配 {@code [name]}（name 不含右方括号），与出图校验同口径。 */
    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");

    /** 单个方括号内可能并列多个资产名时的分隔符（顿号/逗号/斜杠/分号）。 */
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[、，,/／;；]+");

    /** 方括号内的「占位 / 无引用」记号：命中则视为本处无引用、原样保留，不参与清洗。 */
    private static final Set<String> PLACEHOLDER_TOKENS = Set.of(
            "无", "暂无", "无引用", "无引用资产", "空", "none", "null", "n/a", "na", "/", "／", "-", "—");

    /** 被剔除引用的内部占位标记（清洗中间态，最终会连同相邻分隔符一起清理）。 */
    private static final String REMOVAL_MARK = "\u0000";

    /**
     * 清洗结果。
     */
    public static final class Result
    {
        /** 清洗后的引用信息文本 */
        private final String text;
        /** 被剔除的字典外引用名（供日志排查，可能为空） */
        private final List<String> removed;

        Result(String text, List<String> removed)
        {
            this.text = text;
            this.removed = removed;
        }

        public String getText() { return text; }

        public List<String> getRemoved() { return removed; }

        public boolean hasRemoval() { return CollectionUtil.isNotEmpty(removed); }
    }

    /**
     * 资产引用名归一化：把「横线类」分隔符（连字符/全角横线/连接号/en dash）统一成下划线后再比对，
     * 消除 LLM 偶发把 {@code _} 写成 {@code -} 造成的误判。仅用于匹配比对，不改写落库名。
     */
    public static String normalize(String s)
    {
        if (s == null) { return ""; }
        String t = StrUtil.trim(s);
        return t.replace('-', '_').replace('－', '_').replace('‐', '_').replace('–', '_');
    }

    /**
     * 清洗引用信息文本：剔除白名单之外的 {@code [名称]} 引用。
     *
     * @param refText      原始引用信息文本（分镜脚本 LLM 产出）
     * @param whitelistRaw 可引用资产名白名单（未归一化的原始名，内部会统一归一化后比对）
     * @return 清洗结果；{@code refText} 空、或白名单空时原样返回
     */
    public static Result sanitize(String refText, Collection<String> whitelistRaw)
    {
        if (StrUtil.isBlank(refText))
        {
            return new Result(refText, Collections.emptyList());
        }
        // 安全兜底：白名单为空（大概率资产尚未出图）时不清洗，避免把合法引用全部误删
        if (CollectionUtil.isEmpty(whitelistRaw))
        {
            return new Result(refText, Collections.emptyList());
        }
        // 白名单归一化到 Set，O(1) 查存在性
        Set<String> whitelist = new HashSet<>();
        for (String nm : whitelistRaw)
        {
            if (StrUtil.isNotBlank(nm)) { whitelist.add(normalize(nm)); }
        }

        List<String> removed = new ArrayList<>();
        Matcher matcher = BRACKET_PATTERN.matcher(refText);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find())
        {
            String inner = StrUtil.trim(matcher.group(1));
            if (shouldKeep(inner, whitelist))
            {
                // 保留：原样写回（quoteReplacement 防止 $ / \ 被当作替换语法）
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            }
            else
            {
                // 剔除：先替换为内部标记，最后统一清理标记与相邻分隔符
                removed.add(inner);
                matcher.appendReplacement(buffer, REMOVAL_MARK);
            }
        }
        matcher.appendTail(buffer);

        String cleaned = removed.isEmpty() ? refText : cleanupMarks(buffer.toString());
        return new Result(cleaned, removed);
    }

    /**
     * 判定某个方括号内的引用是否保留：。
     */
    private static boolean shouldKeep(String inner, Set<String> whitelist)
    {
        if (StrUtil.isBlank(inner) || isPlaceholder(inner)) { return true; }
        if (hitsWhitelist(inner, whitelist)) { return true; }
        for (String token : SPLIT_PATTERN.split(inner))
        {
            String name = StrUtil.trim(token);
            if (StrUtil.isBlank(name) || isPlaceholder(name)) { continue; }
            if (!hitsWhitelist(name, whitelist))
            {
                return false; // 存在字典外的并列名 → 整个引用剔除
            }
        }
        return true;
    }

    /**
     * 白名单命中判定：按候选键（精确 / 方位后缀回退）逐一比对，
     * 与 {@link StoryboardImageReferenceResolver#candidateLookupKeys} 同源，
     * 避免把出图侧可回退解析的引用（如整图场景被写成 {@code [X_反打]}）误当杜撰剔除。
     */
    private static boolean hitsWhitelist(String name, Set<String> whitelist)
    {
        for (String key : StoryboardImageReferenceResolver.candidateLookupKeys(name))
        {
            if (whitelist.contains(key)) { return true; }
        }
        return false;
    }

    /** 是否为「占位 / 无引用」记号（大小写不敏感）。 */
    private static boolean isPlaceholder(String token)
    {
        if (StrUtil.isBlank(token)) { return true; }
        return PLACEHOLDER_TOKENS.contains(StrUtil.trim(token).toLowerCase());
    }

    /**
     * 清理剔除标记及其相邻分隔符，避免遗留 {@code 角色：、[x]} / {@code [x]、} 等破碎片段。
     */
    private static String cleanupMarks(String s)
    {
        s = s.replaceAll("\u0000[\\s、，,/／;；]*", "");
        s = s.replaceAll("[\\s、，,/／;；]*\u0000", "");
        s = s.replace(REMOVAL_MARK, "");
        s = s.replaceAll("([：:])\\s*[、，,/／;；]+", "$1");
        s = s.replaceAll("[、，,/／;；]{2,}", "、");
        return StrUtil.trim(s);
    }
}
