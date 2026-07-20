package com.aid.voice.util;

import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

/**
 * 配音文本（TTS）统一清洗工具：把分镜台词 {@code aid_storyboard.dialogue_text} 中的
 * 结构化标记剥掉，只保留可朗读的台词正文，避免 TTS 把标记读出来、也避免用户为标记字符付费。
 *
 * <p>需要清洗的标记形态（来自分镜脚本 / 视觉导演产出）：</p>
 * <ul>
 *   <li>{@code @音频N[音频-角色名]} —— 角色配音引用占位（系统私有，模型/TTS 均不识别）；</li>
 *   <li>段首角色标记 {@code [罗峰_初始形象]：} / {@code 【罗峰_初始形象，低沉独白】说：} —— 说话人标注；</li>
 *   <li>{@code |} / {@code ｜} 段分隔符 —— 多角色台词拼接符，转为换行保留停顿语义；</li>
 *   <li>{@code 台词：} 行前缀 —— 视觉导演模板结构词。</li>
 * </ul>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li><strong>幂等</strong>：对不含标记的普通文本原样返回（仅 trim / 空行压缩）；</li>
 *   <li><strong>保守</strong>：台词正文（含引号）一律不动，仅删除可确定的结构标记；
 *       角色标记只在「段首」匹配且长度受限（≤{@value #ROLE_MARK_MAX_LEN} 字符），防止误吞正文中的方括号内容；</li>
 *   <li>清洗只作用于 TTS 下发文本与 {@code aid_audio_record.tts_text} 落库，
 *       {@code dialogue_text} 原文（带角色标记，工作台展示需要）不受影响。</li>
 * </ul>
 *
 * @author 视觉AID
 */
public final class DialogueTextSanitizer
{
    private DialogueTextSanitizer()
    {
    }

    /** 段首角色标记内容最大长度：角色名_形象名（可带情感标注），超长视为正文不清洗 */
    private static final int ROLE_MARK_MAX_LEN = 50;

    /** {@code @音频N[...]} 占位（与 ReferencePromptSanitizer.AUDIO_REF_PLACEHOLDER 同构，删除处理） */
    private static final Pattern AUDIO_REF_PLACEHOLDER = Pattern.compile("@音频\\d+\\[[^\\]]*\\]");

    /** 段分隔符：半角/全角竖线（左右可带空白）→ 换行 */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s*[|｜]\\s*");

    /**
     * 段首角色标记：行首（MULTILINE）出现的 {@code [xxx]} / {@code 【xxx】}，
     * 后接可选 {@code 说} 与可选冒号（半角/全角）。内容长度受限，防止吞掉正文方括号。
     * 示例命中：{@code [罗峰_初始形象]："} 的标记部分、{@code 【巴巴塔_初始形象，机械电子音】说：} 的标记部分。
     */
    private static final Pattern LEADING_ROLE_MARK = Pattern.compile(
            "(?m)^\\s*[\\[【][^\\]】\\n]{1," + ROLE_MARK_MAX_LEN + "}[\\]】]\\s*(?:说\\s*)?[:：]?\\s*");

    /** 行首 {@code 台词：} 结构前缀（视觉导演模板词，非台词正文） */
    private static final Pattern LEADING_DIALOGUE_LABEL = Pattern.compile("(?m)^\\s*台词\\s*[:：]\\s*");

    /** 三个以上连续换行压缩为两个（保持段落间隔又不产生大空洞） */
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");

    /**
     * 「无台词」占位整体形态：整段仅为 无/（无）/无台词/暂无台词 等标记（可带括号、句号），
     * 属于分镜脚本对"该镜无台词"的结构化表达，不是可朗读正文。
     */
    private static final Pattern NO_DIALOGUE_PLACEHOLDER = Pattern.compile(
            "^[\\s(（\\[【]*(?:无|无台词|暂无|暂无台词|N/A|无对白)[\\s)）\\]】。.！!]*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 判断文本是否为「无台词」占位（清洗后整体等价于"无"）。
     *
     * @param text 待判定文本（可空）
     * @return true=无台词占位
     */
    public static boolean isNoDialoguePlaceholder(String text) {
        return StrUtil.isNotBlank(text) && NO_DIALOGUE_PLACEHOLDER.matcher(text.strip()).matches();
    }

    /**
     * 清洗台词文本为可朗读正文。
     *
     * @param rawText 原始台词文本（可含角色标记 / 音频占位 / 竖线分隔符）
     * @return 清洗后的纯台词文本；入参为空白或「无台词」占位时返回空字符串
     */
    public static String sanitize(String rawText)
    {
        if (StrUtil.isBlank(rawText))
        {
            return "";
        }
        String cleaned = rawText;
        // 1) 删除 @音频N[...] 占位（先于分段处理，占位内可能含竖线以外的任意字符）
        cleaned = AUDIO_REF_PLACEHOLDER.matcher(cleaned).replaceAll("");
        // 2) 竖线分隔符 → 换行（让每段的段首角色标记都处于行首，供步骤 3 命中）
        cleaned = SEGMENT_SEPARATOR.matcher(cleaned).replaceAll("\n");
        // 3) 删除行首「台词：」结构前缀
        cleaned = LEADING_DIALOGUE_LABEL.matcher(cleaned).replaceAll("");
        // 4) 删除段首角色标记（[角色_形象]： / 【角色_形象，情感】说：）
        cleaned = LEADING_ROLE_MARK.matcher(cleaned).replaceAll("");
        // 5) 压缩多余空行 + 去首尾空白
        cleaned = EXCESSIVE_NEWLINES.matcher(cleaned).replaceAll("\n\n");
        cleaned = cleaned.strip();
        // 6) 「无台词」占位（无/（无）/无台词…）等价于没有正文：返回空串，
        //    否则 TTS 会朗读出"无"字、字幕会烧上"无"字（脏数据源头治理）
        if (isNoDialoguePlaceholder(cleaned))
        {
            return "";
        }
        return cleaned;
    }
}
