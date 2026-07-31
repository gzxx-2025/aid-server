package com.aid.common.text;

import java.util.regex.Pattern;

import cn.hutool.core.util.EscapeUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 富文本 → 纯文本归一化工具：把富文本编辑器写入的 HTML 标签与 HTML 实体还原为纯文本，
 * 供台词解析、TTS 下发、字幕烧录等「只认纯文本」的下游统一消费。
 *
 * <p>处理口径：块级标签转换为换行以保留段落停顿，其余标签与 HTML 注释直接删除，
 * 随后反转义 HTML 实体并把不间断空格归一为普通空格。标签匹配要求形如 {@code <tag>} / {@code </tag>} /
 * {@code <tag attr="x"/>} 的合法标签形态，正文中孤立的大于小于号不受影响。</p>
 *
 * @author 视觉AID
 */
public final class RichTextNormalizer
{
    private RichTextNormalizer()
    {
    }

    /** HTML 注释（含跨行） */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** 块级标签：承载段落/换行语义，统一转为换行 */
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?i)</?(?:p|br|div|li|ul|ol|dl|dt|dd|tr|td|th|table|thead|tbody|h[1-6]"
                    + "|section|article|header|footer|blockquote|pre|hr)(?:\\s[^<>]*)?/?>");

    /** 行内/样式标签：对正文无语义，直接删除 */
    private static final Pattern INLINE_TAG = Pattern.compile(
            "(?i)</?[a-z][a-z0-9]{0,15}(?:\\s[^<>]*)?/?>");

    /** 不间断空格：反转义后可能出现，归一为普通空格以便后续 trim 与断句 */
    private static final Pattern NON_BREAKING_SPACE = Pattern.compile("[\\u00A0\\u2007\\u202F]");

    /** 行尾空白（含转标签后遗留的空格） */
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t]+$");

    /** 行首空白 */
    private static final Pattern LEADING_SPACES = Pattern.compile("(?m)^[ \\t]+");

    /**
     * 富文本归一为纯文本。
     *
     * @param richText 可能含 HTML 标签/实体的文本（可空）
     * @return 纯文本；入参为空白时原样返回
     */
    public static String toPlainText(String richText)
    {
        if (StrUtil.isBlank(richText))
        {
            return richText;
        }
        String text = HTML_COMMENT.matcher(richText).replaceAll("");
        // 块级标签先于行内标签处理，避免 <li>/<p> 被行内规则当普通标签删掉而丢失换行语义
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = INLINE_TAG.matcher(text).replaceAll("");
        // 实体反转义放在标签删除之后：防止 &lt;p&gt; 被还原成标签后再次参与标签匹配
        text = EscapeUtil.unescapeHtml4(text);
        text = NON_BREAKING_SPACE.matcher(text).replaceAll(" ");
        text = LEADING_SPACES.matcher(text).replaceAll("");
        text = TRAILING_SPACES.matcher(text).replaceAll("");
        return text.strip();
    }
}
