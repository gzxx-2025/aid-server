package com.aid.rps.resolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * 业务私有占位 {@code @音频N[音频-角色名]} 的唯一解析入口。
 *
 * <p>配音链路（按角色分段 TTS）与出片链路（参考音频下发）必须共用同一套编号与引用名口径，
 * 因此正则只在本类定义一次，任何一侧都不得自行编写占位正则。</p>
 *
 * @author 视觉AID
 */
public final class StoryboardAudioPlaceholders
{
    /** 占位正则：组1=编号，组2=引用名（允许为空以便识别非法占位） */
    private static final Pattern AUDIO_PLACEHOLDER = Pattern.compile("@音频(\\d+)\\[([^\\]]*)]");

    private StoryboardAudioPlaceholders()
    {
    }

    /**
     * 占位正则（供只需匹配/替换、不需要结构化结果的调用方使用）。
     *
     * @return 编译后的占位正则
     */
    public static Pattern pattern()
    {
        return AUDIO_PLACEHOLDER;
    }

    /**
     * 文本中是否含音频占位。
     *
     * @param text 待检测文本
     * @return 含占位返回 true
     */
    public static boolean contains(String text)
    {
        return StrUtil.isNotBlank(text) && AUDIO_PLACEHOLDER.matcher(text).find();
    }

    /**
     * 取文本中首个占位的编号。
     *
     * @param text 待解析文本
     * @return 首个占位编号；无占位或编号非法返回 null
     */
    public static Integer firstIndex(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return null;
        }
        Matcher matcher = AUDIO_PLACEHOLDER.matcher(text);
        if (!matcher.find())
        {
            return null;
        }
        return parseIndex(matcher.group(1));
    }

    /**
     * 删除文本中的全部音频占位。
     *
     * @param text 待处理文本
     * @return 删除占位后的文本；入参为空原样返回
     */
    public static String removeAll(String text)
    {
        return StrUtil.isBlank(text) ? text : AUDIO_PLACEHOLDER.matcher(text).replaceAll("");
    }

    /**
     * 结构化解析文本中的全部占位。
     *
     * @param text 待解析文本
     * @return 编号到引用名的有序映射与冲突标记，绝不返回 null
     */
    public static PlaceholderResult parse(String text)
    {
        PlaceholderResult result = new PlaceholderResult();
        if (StrUtil.isBlank(text))
        {
            return result;
        }
        Matcher matcher = AUDIO_PLACEHOLDER.matcher(text);
        while (matcher.find())
        {
            Integer index = parseIndex(matcher.group(1));
            String name = StrUtil.trimToNull(matcher.group(2));
            if (Objects.isNull(index) || StrUtil.isBlank(name))
            {
                result.conflicted = true;
                continue;
            }
            String existing = result.names.putIfAbsent(index, name);
            // 同一编号绑定不同引用名属于提示词自身矛盾，必须让上游感知
            if (StrUtil.isNotBlank(existing) && !Objects.equals(existing, name))
            {
                result.conflicted = true;
            }
        }
        return result;
    }

    private static Integer parseIndex(String value)
    {
        try
        {
            return Integer.valueOf(value);
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    /** 占位解析结果。 */
    @Getter
    public static final class PlaceholderResult
    {
        /** 编号到引用名的有序映射。 */
        private final Map<Integer, String> names = new LinkedHashMap<>();

        /** 是否存在编号冲突或非法占位。 */
        private boolean conflicted;

        /**
         * 最大编号后的下一个可用编号。
         *
         * @return 下一个可用编号，无占位时为 1
         */
        public int nextIndex()
        {
            return names.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
        }
    }
}
