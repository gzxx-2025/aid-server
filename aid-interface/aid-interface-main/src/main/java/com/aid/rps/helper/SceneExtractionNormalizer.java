package com.aid.rps.helper;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 场景提取结果归一化器。
 *
 * <p>LLM 只负责提供结构化候选值，场景名称最终由服务端依据
 * {@code specificLocation + "_" + timeOfDay} 统一生成，防止模型输出
 * “_无”“_未知”或 name/timeOfDay 不一致时污染场景资产。</p>
 */
public final class SceneExtractionNormalizer
{
    /** 原文无法确定时间时使用的统一业务默认值。 */
    public static final String DEFAULT_TIME_OF_DAY = "上午";

    private static final Set<String> STANDARD_TIME_OF_DAY = Set.of(
            "凌晨", "早晨", "上午", "中午", "下午", "黄昏", "夜晚", "深夜", "子夜");

    private static final Set<String> LOCATION_PLACEHOLDERS = Set.of(
            "无", "未知", "未说明", "不明确", "无明确", "空间锚点");

    private static final Set<String> TIME_PLACEHOLDERS = Set.of(
            "无", "未知", "未说明", "不明确", "无明确", "未定", "空");

    /** 时间同义词统一映射为系统九档时间。 */
    private static final Map<String, String> TIME_ALIASES = buildTimeAliases();

    private SceneExtractionNormalizer()
    {
    }

    /**
     * 归一化单条场景，并把权威字段回写到当前 JSON 节点供后续落库使用。
     *
     * @param item LLM 返回的单条 locations 元素
     * @return 服务端生成的权威场景信息
     */
    public static NormalizedScene normalize(JsonNode item)
    {
        if (!(item instanceof ObjectNode objectNode))
        {
            throw new IllegalArgumentException("场景结构无效");
        }

        String rawName = readText(item, "name");
        String rawSpecificLocation = readText(item, "specificLocation", "specific_location");
        String specificLocation = cleanLocation(extractLocationFromName(rawSpecificLocation));
        if (isInvalidLocation(specificLocation))
        {
            specificLocation = cleanLocation(extractLocationFromName(rawName));
        }
        if (isInvalidLocation(specificLocation))
        {
            throw new IllegalArgumentException("场景地点缺失");
        }

        ResolvedTime resolvedTime = resolveTime(item, rawName);
        String canonicalName = specificLocation + "_" + resolvedTime.timeOfDay();

        // 服务端字段为最终权威值，禁止模型的自由文本直接进入资产名称与剧情时间。
        objectNode.put("name", canonicalName);
        objectNode.put("specificLocation", specificLocation);
        objectNode.put("timeOfDay", resolvedTime.timeOfDay());

        return new NormalizedScene(objectNode, canonicalName, specificLocation,
                resolvedTime.timeOfDay(), resolvedTime.defaulted());
    }

    /** 判断给定值是否为系统支持的标准时间。 */
    public static boolean isStandardTimeOfDay(String value)
    {
        return StrUtil.isNotBlank(value) && STANDARD_TIME_OF_DAY.contains(value.trim());
    }

    private static ResolvedTime resolveTime(JsonNode item, String rawName)
    {
        String normalized = normalizeTime(readText(item, "timeOfDay", "time_of_day"));
        if (Objects.nonNull(normalized))
        {
            return new ResolvedTime(normalized, false);
        }

        normalized = normalizeTime(extractTimeSuffix(rawName));
        if (Objects.nonNull(normalized))
        {
            return new ResolvedTime(normalized, false);
        }

        // 结构字段和名称均不可靠时，仅从当前场次文本中的明确时间词兜底识别。
        normalized = inferTimeFromText(readText(item, "plotContent", "plot_content"));
        if (Objects.nonNull(normalized))
        {
            return new ResolvedTime(normalized, false);
        }

        return new ResolvedTime(DEFAULT_TIME_OF_DAY, true);
    }

    private static String normalizeTime(String rawTime)
    {
        if (StrUtil.isBlank(rawTime))
        {
            return null;
        }
        String value = rawTime.trim();
        if (TIME_PLACEHOLDERS.contains(value))
        {
            return null;
        }
        if (STANDARD_TIME_OF_DAY.contains(value))
        {
            return value;
        }
        return TIME_ALIASES.get(value);
    }

    private static String inferTimeFromText(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return null;
        }

        int earliestIndex = Integer.MAX_VALUE;
        String matchedTime = null;
        for (Map.Entry<String, String> entry : TIME_ALIASES.entrySet())
        {
            int index = text.indexOf(entry.getKey());
            if (index >= 0 && index < earliestIndex)
            {
                earliestIndex = index;
                matchedTime = entry.getValue();
            }
        }
        return matchedTime;
    }

    private static String extractLocationFromName(String rawName)
    {
        if (StrUtil.isBlank(rawName))
        {
            return null;
        }
        String name = rawName.trim();
        int separator = name.lastIndexOf('_');
        if (separator < 0)
        {
            return name;
        }

        String suffix = name.substring(separator + 1).trim();
        if (StrUtil.isBlank(suffix)
                || TIME_PLACEHOLDERS.contains(suffix)
                || Objects.nonNull(normalizeTime(suffix)))
        {
            return name.substring(0, separator);
        }
        return name;
    }

    private static String extractTimeSuffix(String rawName)
    {
        if (StrUtil.isBlank(rawName))
        {
            return null;
        }
        int separator = rawName.lastIndexOf('_');
        if (separator < 0 || separator == rawName.length() - 1)
        {
            return null;
        }
        return rawName.substring(separator + 1).trim();
    }

    private static String cleanLocation(String rawLocation)
    {
        if (StrUtil.isBlank(rawLocation))
        {
            return null;
        }
        String location = rawLocation.trim()
                .replace("（推断）", "")
                .replace("(推断)", "")
                .trim();
        if (location.startsWith("INT-") || location.startsWith("EXT-"))
        {
            location = location.substring(4).trim();
        }
        return StrUtil.isBlank(location) ? null : location;
    }

    private static boolean isInvalidLocation(String location)
    {
        return StrUtil.isBlank(location) || LOCATION_PLACEHOLDERS.contains(location.trim());
    }

    private static String readText(JsonNode item, String... keys)
    {
        if (Objects.isNull(item) || keys == null)
        {
            return null;
        }
        for (String key : keys)
        {
            JsonNode value = item.get(key);
            if (Objects.nonNull(value) && !value.isNull() && StrUtil.isNotBlank(value.asText()))
            {
                return value.asText().trim();
            }
        }
        return null;
    }

    private static Map<String, String> buildTimeAliases()
    {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("凌晨", "凌晨");
        aliases.put("黎明", "凌晨");
        aliases.put("破晓", "凌晨");
        aliases.put("早晨", "早晨");
        aliases.put("清晨", "早晨");
        aliases.put("上午", "上午");
        aliases.put("白天", "上午");
        aliases.put("日间", "上午");
        aliases.put("中午", "中午");
        aliases.put("正午", "中午");
        aliases.put("下午", "下午");
        aliases.put("午后", "下午");
        aliases.put("黄昏", "黄昏");
        aliases.put("傍晚", "黄昏");
        aliases.put("夜晚", "夜晚");
        aliases.put("晚上", "夜晚");
        aliases.put("夜间", "夜晚");
        aliases.put("深夜", "深夜");
        aliases.put("午夜", "子夜");
        aliases.put("子夜", "子夜");
        return Map.copyOf(aliases);
    }

    /** 归一化后的单条场景权威值。 */
    public record NormalizedScene(ObjectNode item, String canonicalName, String specificLocation,
                                  String timeOfDay, boolean defaulted)
    {
    }

    private record ResolvedTime(String timeOfDay, boolean defaulted)
    {
    }
}
