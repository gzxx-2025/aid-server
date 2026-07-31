package com.aid.script.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * 剧本自动分集解析器：按用户提供的分集词样例把整篇剧本切成有序单集。
 * 分集词两种形态：含序数样例（如「第一集」「第1话」）识别其中数字段，泛化为「前缀 + 任意序数 + 后缀」
 * 行级模式，「第一集 / 第2集 / 第十三集」均可命中；纯分隔词（如「===分集===」）按行首字面量匹配，
 * 每次出现即为一集起点。分集行本身不计入正文，其序数/分隔词之后的剩余文本作为该集标题；
 * 首个分集行之前的内容（引言/楔子）并入第一集正文开头，保证不丢内容。纯文本解析、无状态。
 *
 * @author 视觉AID
 */
public final class ScriptEpisodeSplitter {

    /** 序数字符集（阿拉伯数字 + 常用中文数字） */
    private static final String ORDINAL_CHARS = "0-9零一二三四五六七八九十百千两〇";

    /** 分集词样例中的序数段（用于把样例泛化为模式） */
    private static final Pattern SAMPLE_ORDINAL = Pattern.compile("[" + ORDINAL_CHARS + "]+");

    /** 标题前的常见分隔符（冒号/破折号/顿号/点号/空白） */
    private static final String TITLE_SEPARATOR = "[\\s:：\\-—~·、.。]*";

    private ScriptEpisodeSplitter() {
    }

    /** 单集解析结果 */
    @Data
    public static class EpisodeSegment {
        /** 顺序集号（按出现顺序 1 起，入库 episodeNo 的依据） */
        private int sequenceNo;
        /** 分集行解析出的原始序数（如「十三」「3」；纯分隔词模式为 null） */
        private String rawOrdinal;
        /** 单集标题（分集行剩余文本；为空时由调用方回退「第N集」） */
        private String title;
        /** 该集正文（不含分集行） */
        private String content;
    }

    /**
     * 按分集词样例解析整篇剧本。
     *
     * @param scriptText     整篇剧本文本（调用方保证非空）
     * @param episodeKeyword 分集词样例（如「第一集」；空白回退「第一集」）
     * @return 有序单集列表；未命中任何分集行返回空列表
     */
    public static List<EpisodeSegment> split(String scriptText, String episodeKeyword) {
        String keyword = StrUtil.blankToDefault(episodeKeyword, "第一集").trim();
        Pattern linePattern = buildLinePattern(keyword);

        List<EpisodeSegment> segments = new ArrayList<>();
        StringBuilder preface = new StringBuilder();
        EpisodeSegment current = null;
        StringBuilder currentContent = null;

        for (String line : scriptText.split("\r?\n", -1)) {
            Matcher matcher = linePattern.matcher(line);
            if (matcher.matches()) {
                // 命中分集行：收尾上一集，开启新集
                if (Objects.nonNull(current)) {
                    current.setContent(currentContent.toString().strip());
                    segments.add(current);
                }
                current = new EpisodeSegment();
                current.setSequenceNo(segments.size() + 1);
                // 组1=序数（纯分隔词模式无该组），末组=标题余文
                current.setRawOrdinal(matcher.groupCount() >= 2 ? StrUtil.trimToNull(matcher.group(1)) : null);
                current.setTitle(StrUtil.trimToNull(matcher.group(matcher.groupCount())));
                currentContent = new StringBuilder();
                continue;
            }
            if (Objects.isNull(current)) {
                // 首个分集行之前的引言：暂存，稍后并入第一集
                preface.append(line).append('\n');
            } else {
                currentContent.append(line).append('\n');
            }
        }
        if (Objects.nonNull(current)) {
            current.setContent(currentContent.toString().strip());
            segments.add(current);
        }
        // 引言并入第一集正文开头（不丢内容）
        String prefaceText = preface.toString().strip();
        if (!segments.isEmpty() && StrUtil.isNotBlank(prefaceText)) {
            EpisodeSegment first = segments.get(0);
            first.setContent(StrUtil.isBlank(first.getContent())
                    ? prefaceText : prefaceText + "\n\n" + first.getContent());
        }
        return segments;
    }

    /**
     * 提取单集描述：正文压缩空白后取前 N 字，超长追加省略号。
     *
     * @param content   单集正文
     * @param maxLength 描述最大字数
     * @return 描述文本；正文为空返回空串
     */
    public static String summarize(String content, int maxLength) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String compact = content.replaceAll("\\s+", "");
        if (compact.length() <= maxLength) {
            return compact;
        }
        return compact.substring(0, maxLength) + "...";
    }

    /**
     * 把分集词样例泛化为行级匹配模式。
     * 含序数段 → 「前缀 + ([序数]+) + 后缀 + 分隔 + (标题)」；纯分隔词 → 「字面量 + 分隔 + (标题)」。
     *
     * @param keyword 分集词样例
     * @return 行匹配模式
     */
    private static Pattern buildLinePattern(String keyword) {
        Matcher ordinal = SAMPLE_ORDINAL.matcher(keyword);
        if (ordinal.find()) {
            String prefix = keyword.substring(0, ordinal.start());
            String suffix = keyword.substring(ordinal.end());
            return Pattern.compile("^\\s*" + quoteIfNotEmpty(prefix)
                    + "([" + ORDINAL_CHARS + "]+)" + quoteIfNotEmpty(suffix)
                    + TITLE_SEPARATOR + "(.*?)\\s*$");
        }
        // 纯分隔词：行首字面量匹配（无序数组）
        return Pattern.compile("^\\s*" + Pattern.quote(keyword) + TITLE_SEPARATOR + "(.*?)\\s*$");
    }

    /**
     * 非空片段做正则字面量转义。
     *
     * @param part 样例片段
     * @return 转义后的片段（空串原样）
     */
    private static String quoteIfNotEmpty(String part) {
        return StrUtil.isEmpty(part) ? "" : Pattern.quote(part);
    }
}
