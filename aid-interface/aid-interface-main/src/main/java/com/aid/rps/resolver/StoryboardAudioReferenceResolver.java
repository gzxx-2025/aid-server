package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * 分镜台词（{@code aid_storyboard.dialogue_text} / 视觉导演 video_prompt 台词行）的结构化解析器：
 * 把多角色台词串拆成有序台词段，供「配音预检（角色→音色绑定校验）」「按角色分段 TTS」消费。
 *
 * <p>与 {@link StoryboardImageReferenceResolver}（{@code @图片N[name]} → 现成资产 URL）不同，
 * {@code @音频N[音频-角色名]} 指向的是「角色的音色绑定」，真实音频需按台词先 TTS 才存在，
 * 因此本解析器只做<strong>纯文本结构化</strong>（无 DB 依赖、可重入），
 * 角色资产匹配与音色绑定反查由调用方（预检 Service）完成。</p>
 *
 * <p>支持的台词形态（可混合出现）：</p>
 * <ul>
 *   <li>视觉导演台词行：{@code 台词：【罗峰_初始形象，低沉独白】@音频1[音频-罗峰_初始形象]说："…"，语速缓慢深沉。}</li>
 *   <li>分镜台词列简格式：{@code [罗峰_初始形象]："…" | [巴巴塔_初始形象]："…"}</li>
 *   <li>无任何标记的纯文本 → 单段旁白（{@code roleRef=null}）</li>
 * </ul>
 *
 * @author 视觉AID
 */
@Component
public class StoryboardAudioReferenceResolver
{
    /** 段首角色标记内容最大长度（角色名_形象名 + 情感标注），与 DialogueTextSanitizer 口径一致 */
    private static final int ROLE_MARK_MAX_LEN = 50;

    /** 段分隔符：半角/全角竖线或换行 */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s*[|｜\\n]\\s*");

    /** 行首「台词：」结构前缀 */
    private static final Pattern LEADING_DIALOGUE_LABEL = Pattern.compile("^\\s*台词\\s*[:：]\\s*");

    /** 段首角色标记：{@code [xxx]} / {@code 【xxx】}（组1=标记内容） */
    private static final Pattern LEADING_ROLE_MARK = Pattern.compile(
            "^\\s*[\\[【]([^\\]】\\n]{1," + ROLE_MARK_MAX_LEN + "})[\\]】]\\s*");

    /** {@code @音频N[...]} 占位（组1=编号 N） */
    private static final Pattern AUDIO_REF = Pattern.compile("@音频(\\d+)\\[[^\\]]*\\]");

    /** 角色标记后的「说：」引导词（可只有冒号） */
    private static final Pattern SAY_PREFIX = Pattern.compile("^\\s*(?:说\\s*)?[:：]?\\s*");

    /** 段尾语速标注：{@code ，语速缓慢深沉。}（组1=语速描述） */
    private static final Pattern SPEED_SUFFIX = Pattern.compile("[，,]\\s*语速([^。.\\n，,]{1,30})[。.]?\\s*$");

    /** 成对引号：开引号 → 闭引号（用于正文剥壳） */
    private static final char[][] QUOTE_PAIRS = {
            {'\u201C', '\u201D'},   // 中文双引号 “ ”
            {'"', '"'},
            {'「', '」'},
            {'『', '』'}
    };

    /**
     * 解析台词文本为有序台词段。
     *
     * @param dialogueText 台词原文（dialogue_text 或 video_prompt 台词行拼接文本）
     * @return 有序台词段列表（index 从 1 起）；入参为空白返回空列表
     */
    public List<DialogueSegment> parse(String dialogueText)
    {
        List<DialogueSegment> segments = new ArrayList<>();
        if (StrUtil.isBlank(dialogueText))
        {
            return segments;
        }
        // 分段：竖线 / 换行统一切开，空段丢弃
        String[] rawSegments = SEGMENT_SEPARATOR.split(dialogueText.strip());
        int index = 0;
        for (String rawSegment : rawSegments)
        {
            if (StrUtil.isBlank(rawSegment))
            {
                continue;
            }
            DialogueSegment segment = parseSegment(rawSegment.strip());
            if (segment == null)
            {
                continue;
            }
            segment.setIndex(++index);
            segments.add(segment);
        }
        return segments;
    }

    /**
     * 解析单段台词；正文为空（纯标记段）返回 null。
     */
    private DialogueSegment parseSegment(String rawSegment)
    {
        DialogueSegment segment = new DialogueSegment();
        segment.setRawSegment(rawSegment);
        String rest = rawSegment;

        // 1) 删「台词：」结构前缀
        rest = LEADING_DIALOGUE_LABEL.matcher(rest).replaceFirst("");

        // 2) 段首角色标记：【罗峰_初始形象，低沉独白】→ roleRef=罗峰_初始形象，emotionHint=低沉独白
        Matcher roleMark = LEADING_ROLE_MARK.matcher(rest);
        if (roleMark.find())
        {
            applyRoleMark(segment, roleMark.group(1));
            rest = rest.substring(roleMark.end());
        }

        // 3) @音频N[...] 占位：提取编号后删除
        Matcher audioRef = AUDIO_REF.matcher(rest);
        if (audioRef.find())
        {
            try
            {
                segment.setAudioRefN(Integer.parseInt(audioRef.group(1)));
            }
            catch (NumberFormatException ignored)
            {
                // 编号异常仅忽略该占位，不影响正文解析
            }
            rest = AUDIO_REF.matcher(rest).replaceAll("");
        }

        // 4) 删「说：」引导词（仅角色段；旁白段没有标记不会误删正文冒号——SAY_PREFIX 只在此分支应用）
        if (StrUtil.isNotBlank(segment.getRoleRef()))
        {
            rest = SAY_PREFIX.matcher(rest).replaceFirst("");
        }

        // 5) 段尾语速标注：，语速缓慢深沉。→ speedHint=缓慢深沉（正则锚定串尾，截断即可）
        Matcher speed = SPEED_SUFFIX.matcher(rest);
        if (speed.find())
        {
            segment.setSpeedHint(StrUtil.trimToNull(speed.group(1)));
            rest = rest.substring(0, speed.start());
        }

        // 6) 正文剥引号壳（仅整段被一对引号完整包裹时）
        rest = unquote(rest.strip());
        if (StrUtil.isBlank(rest))
        {
            return null;
        }
        segment.setText(rest);
        segment.setCharCount(rest.length());
        return segment;
    }

    /**
     * 拆角色标记内容：第一个逗号前为角色引用名（角色名_形象名），其余合并为情感提示。
     */
    private void applyRoleMark(DialogueSegment segment, String markContent)
    {
        String content = StrUtil.trimToEmpty(markContent);
        if (StrUtil.isBlank(content))
        {
            return;
        }
        String[] parts = content.split("[，,]", 2);
        String roleRef = StrUtil.trimToNull(parts[0]);
        segment.setRoleRef(roleRef);
        if (parts.length > 1)
        {
            segment.setEmotionHint(StrUtil.trimToNull(parts[1]));
        }
        if (StrUtil.isNotBlank(roleRef))
        {
            // 主名 = 第一个下划线前（罗峰_初始形象 → 罗峰）；无下划线时主名即引用名
            int underscore = roleRef.indexOf('_');
            segment.setRoleName(underscore > 0 ? roleRef.substring(0, underscore) : roleRef);
        }
    }

    /** 整段被一对引号完整包裹时剥壳，其余原样返回 */
    private String unquote(String text)
    {
        if (StrUtil.isBlank(text) || text.length() < 2)
        {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        for (char[] pair : QUOTE_PAIRS)
        {
            if (first == pair[0] && last == pair[1])
            {
                return text.substring(1, text.length() - 1).strip();
            }
        }
        return text;
    }

    /**
     * 单段台词的结构化结果。
     */
    @Data
    public static class DialogueSegment
    {
        /** 段序号（1 基，按台词出现顺序） */
        private int index;

        /** 角色引用名（角色名_形象名，如 罗峰_初始形象）；旁白段为 null */
        private String roleRef;

        /** 角色主名（引用名第一个下划线前，如 罗峰）；旁白段为 null */
        private String roleName;

        /** 情感提示（角色标记逗号后内容，如 低沉独白）；可空 */
        private String emotionHint;

        /** 语速提示（段尾「语速xx」标注，如 缓慢深沉）；可空 */
        private String speedHint;

        /** {@code @音频N} 占位编号；台词未带占位时为 null */
        private Integer audioRefN;

        /** 可朗读台词正文（已剥标记与引号壳） */
        private String text;

        /** 正文字符数（预计时长与计费预估依据） */
        private int charCount;

        /** 原始段文本（排查用，不下发前端） */
        private String rawSegment;

        /** 是否旁白段（无角色标记） */
        public boolean isNarration()
        {
            return StrUtil.isBlank(roleRef);
        }
    }
}
