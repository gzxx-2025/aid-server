package com.aid.compose.util;

import java.util.ArrayList;
import java.util.List;

import cn.hutool.core.util.StrUtil;

/**
 * 成片字幕分屏切分工具：把一段台词切成「一屏一句」的字幕片段序列，
 * 切分优先级为换行 → 句末标点 → 句内停顿标点 → 按单屏字数均分硬切，
 * 常规台词保证每屏 7～12 字且尽量落在语义边界上，片段保持原文顺序且不含换行。
 * 整段不足 7 字时原样保留；13 字属于无法同时满足上下限的边界情况，会均衡为 7 字和 6 字两屏。
 *
 * @author 视觉AID
 */
public final class SubtitleScreenSplitter {

    private SubtitleScreenSplitter() {
    }

    /** 单屏最大字数兜底值（配置缺失或非法时使用） */
    private static final int DEFAULT_MAX_CHARS = 10;

    /** 单屏正文期望最少字数 */
    private static final int MIN_SCREEN_CHARS = 7;

    /** 单屏正文硬性最多字数 */
    private static final int MAX_SCREEN_CHARS = 12;

    /** 句末标点：一句台词的天然边界，切分后保留在句尾 */
    private static final String SENTENCE_END_MARKS = "。！？!?；;…";

    /** 句内停顿标点：整句仍超屏时的次级切分点，保留在片段尾部 */
    private static final String CLAUSE_MARKS = "，,、";

    /** 收尾符号：跟在标点后的右引号/右括号等，需与前一片段绑定，避免单符号独占一屏 */
    private static final String TRAILING_MARKS = "”』」’）)】]》>\"'";

    /**
     * 台词分屏切分。
     *
     * @param text     字幕文本（可含换行，代表多段台词）
     * @param maxChars 单屏优先最大字数（≤0 时使用兜底值，最终限定在 7～12 字）
     * @return 按播放顺序排列的字幕片段；入参空白时返回空列表
     */
    public static List<String> split(String text, int maxChars) {
        List<String> screens = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return screens;
        }
        int configuredLimit = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        int limit = Math.max(MIN_SCREEN_CHARS, Math.min(configuredLimit, MAX_SCREEN_CHARS));
        for (String line : text.split("\\R")) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            List<String> lineScreens = new ArrayList<>();
            for (String sentence : splitByMarks(trimmedLine, SENTENCE_END_MARKS)) {
                if (charCount(sentence) <= limit) {
                    lineScreens.add(sentence);
                    continue;
                }
                for (String clause : splitByMarks(sentence, CLAUSE_MARKS)) {
                    if (charCount(clause) <= limit) {
                        lineScreens.add(clause);
                        continue;
                    }
                    lineScreens.addAll(splitEvenly(clause, limit));
                }
            }
            screens.addAll(rebalanceShortScreens(lineScreens));
        }
        return screens;
    }

    /**
     * 语义切分产生短屏时，按整行重新均衡，避免画面上连续出现一两个字的字幕。
     * 已满足 7～12 字的语义分屏保持不变；短台词不补字、不跨换行合并。
     *
     * @param screens 一行台词的初步分屏
     * @return 字数均衡后的分屏
     */
    private static List<String> rebalanceShortScreens(List<String> screens) {
        if (screens.isEmpty()) {
            return screens;
        }
        boolean alreadyBalanced = screens.stream().allMatch(screen -> {
            int count = charCount(screen);
            return count >= MIN_SCREEN_CHARS && count <= MAX_SCREEN_CHARS;
        });
        if (alreadyBalanced) {
            return screens;
        }
        String combined = String.join("", screens);
        if (charCount(combined) <= MAX_SCREEN_CHARS) {
            return List.of(combined);
        }
        return splitEvenly(combined, MAX_SCREEN_CHARS);
    }

    /**
     * 字数统计：按码点计数，代理对（如 emoji）算一个字。
     *
     * @param text 文本
     * @return 字数
     */
    public static int charCount(String text) {
        return StrUtil.isEmpty(text) ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * 按指定标点集合切分：标点及其后续的连续标点、收尾符号一并归入前一片段。
     *
     * @param text  待切分文本（无换行）
     * @param marks 切分标点集合
     * @return 片段列表
     */
    private static List<String> splitByMarks(String text, String marks) {
        List<String> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            buffer.append(current);
            if (marks.indexOf(current) < 0) {
                continue;
            }
            // 连续标点与右引号/右括号并入当前片段，防止「！」「」」这类单符号独占一屏
            while (i + 1 < text.length() && isBoundaryFollower(text.charAt(i + 1), marks)) {
                buffer.append(text.charAt(++i));
            }
            appendPart(parts, buffer);
        }
        appendPart(parts, buffer);
        return parts;
    }

    /**
     * 判断字符是否应跟随在切分点之后（连续标点或收尾符号）。
     *
     * @param ch    待判定字符
     * @param marks 当前切分标点集合
     * @return true=需并入前一片段
     */
    private static boolean isBoundaryFollower(char ch, String marks) {
        return marks.indexOf(ch) >= 0 || TRAILING_MARKS.indexOf(ch) >= 0;
    }

    /**
     * 收集一个片段：纯标点片段并入前一片段，避免出现没有正文的字幕屏。
     *
     * @param parts  片段列表
     * @param buffer 片段缓冲（收集后清空）
     */
    private static void appendPart(List<String> parts, StringBuilder buffer) {
        String part = buffer.toString().trim();
        buffer.setLength(0);
        if (part.isEmpty()) {
            return;
        }
        if (hasContent(part) || parts.isEmpty()) {
            parts.add(part);
            return;
        }
        parts.set(parts.size() - 1, parts.get(parts.size() - 1) + part);
    }

    /**
     * 判断片段是否含正文字符（非切分标点、非收尾符号、非空白）。
     *
     * @param part 片段
     * @return true=含正文
     */
    private static boolean hasContent(String part) {
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (Character.isWhitespace(ch)
                    || SENTENCE_END_MARKS.indexOf(ch) >= 0
                    || CLAUSE_MARKS.indexOf(ch) >= 0
                    || TRAILING_MARKS.indexOf(ch) >= 0) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 无标点长句均分硬切：切成 ceil(字数/上限) 屏，各屏字数尽量相等，避免末屏只剩一两个字。
     *
     * @param text  待切分文本
     * @param limit 单屏最大字数
     * @return 片段列表
     */
    private static List<String> splitEvenly(String text, int limit) {
        int total = charCount(text);
        int screens = (total + limit - 1) / limit;
        List<String> result = new ArrayList<>(screens);
        int base = total / screens;
        int extra = total % screens;
        int codePointIndex = 0;
        for (int i = 0; i < screens; i++) {
            int size = base + (i < extra ? 1 : 0);
            int begin = text.offsetByCodePoints(0, codePointIndex);
            int end = text.offsetByCodePoints(0, codePointIndex + size);
            result.add(text.substring(begin, end));
            codePointIndex += size;
        }
        return result;
    }
}
