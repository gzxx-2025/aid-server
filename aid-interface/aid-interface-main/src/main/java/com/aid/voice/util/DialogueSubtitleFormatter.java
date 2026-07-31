package com.aid.voice.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aid.rps.resolver.StoryboardAudioReferenceResolver;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver.DialogueSegment;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 台词字幕格式化工具：把分镜台词 {@code aid_storyboard.dialogue_text} 的带标记原文
 * 转成成片字幕友好的「人物：说的话」展示形态。
 *
 * <p>转换示例：</p>
 * <pre>
 * [天兵_初始形象]：「区区弼马温，未得宣召，不得擅闯凌霄！」
 *   → 天兵：区区弼马温未得宣召不得擅闯凌霄
 * </pre>
 *
 * <p>转换规则：</p>
 * <ul>
 *   <li>段首角色标记 {@code [角色名_形象名]} / {@code 【角色名_形象名，情感】} → 只保留角色主名（下划线前），
 *       情感/形象标注对观众是噪声，全部剥掉；旁白按角色写法（如 {@code [旁白_初始形象]}）同样输出「旁白：…」；</li>
 *   <li>无角色标记的纯文本段 → 统一输出「旁白：正文」；</li>
 *   <li>{@code @音频N[...]} 占位、{@code 台词：} 前缀、段尾语速标注、正文引号壳统一剥掉；</li>
 *   <li>正文中的中英文标点统一移除，仅保留人物与正文之间的全角冒号；</li>
 *   <li>竖线分隔的多段台词 → 逐段转换后按换行拼接；</li>
 *   <li><strong>幂等</strong>：已是「人物：说的话」时保留人物结构并重新执行展示清洗。</li>
 * </ul>
 *
 * <p>本工具只影响「展示/烧录字幕」口径；{@code dialogue_text} 原文（工作台编辑用）与
 * TTS 清洗口径（{@link DialogueTextSanitizer}）均不受影响。</p>
 *
 * @author 视觉AID
 */
public final class DialogueSubtitleFormatter
{
    /** 台词结构化解析器（无状态、纯文本解析，可安全静态复用） */
    private static final StoryboardAudioReferenceResolver RESOLVER = new StoryboardAudioReferenceResolver();

    /** 已格式化的“人物：正文”结构。 */
    private static final Pattern DISPLAY_LINE = Pattern.compile("^([^：:\\r\\n]{1,40})[：:](.+)$");

    /** 成片字幕禁止展示的中英文标点；人名与正文之间的全角冒号由格式化逻辑单独保留。 */
    private static final Pattern SUBTITLE_PUNCTUATION = Pattern.compile(
            "[，,。.!！?？；;、：:“”‘’\\\"'…（）()【】\\[\\]《》<>]");

    private DialogueSubtitleFormatter()
    {
    }

    /**
     * 台词原文 → 「人物：说的话」字幕文本。
     *
     * @param rawText 台词原文（可含角色标记 / 音频占位 / 竖线分隔符，也可为用户手输纯文本）
     * @return 格式化后的字幕文本（多段按换行拼接）；入参为空白或「无台词」占位返回 null
     */
    public static String format(String rawText)
    {
        if (StrUtil.isBlank(rawText))
        {
            return null;
        }
        // 「无台词」占位（无/（无）/无台词…）不是字幕正文：返回 null，避免成片烧上"无"字
        if (DialogueTextSanitizer.isNoDialoguePlaceholder(rawText))
        {
            return null;
        }
        // 结构化解析：拆段 + 每段解析出角色主名与纯正文（标记/占位/引号壳已剥）
        List<DialogueSegment> segments = RESOLVER.parse(rawText);
        if (CollectionUtil.isEmpty(segments))
        {
            return null;
        }
        List<String> lines = new ArrayList<>(segments.size());
        for (DialogueSegment segment : segments)
        {
            if (StrUtil.isBlank(segment.getText()))
            {
                continue;
            }
            // 每一段必须输出“人物：正文”；结构化角色优先，纯文本统一归为旁白。
            if (StrUtil.isNotBlank(segment.getRoleName()))
            {
                String line = formatCue(segment.getRoleName(), segment.getText());
                if (StrUtil.isNotBlank(line))
                {
                    lines.add(line);
                }
            }
            else
            {
                Matcher matcher = DISPLAY_LINE.matcher(segment.getText().trim());
                String line = matcher.matches()
                        ? formatCue(matcher.group(1), matcher.group(2))
                        : formatCue("旁白", segment.getText());
                if (StrUtil.isNotBlank(line))
                {
                    lines.add(line);
                }
            }
        }
        if (CollectionUtil.isEmpty(lines))
        {
            return null;
        }
        return String.join("\n", lines);
    }

    /**
     * 组装单条展示字幕，保证人名存在并移除正文标点。
     *
     * @param speaker 人物名称
     * @param text    台词正文
     * @return “人物：正文”，正文为空时返回 null
     */
    public static String formatCue(String speaker, String text)
    {
        String body = sanitizeSpokenText(text);
        if (StrUtil.isBlank(body))
        {
            return null;
        }
        String displaySpeaker = StrUtil.blankToDefault(speaker, "旁白").trim();
        return displaySpeaker + "：" + body;
    }

    /**
     * 清洗实际展示的台词正文：移除逗号、句号等中英文标点并收敛多余空白。
     *
     * @param text 原正文
     * @return 无标点正文
     */
    public static String sanitizeSpokenText(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return null;
        }
        String cleaned = SUBTITLE_PUNCTUATION.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return StrUtil.isBlank(cleaned) ? null : cleaned;
    }
}
