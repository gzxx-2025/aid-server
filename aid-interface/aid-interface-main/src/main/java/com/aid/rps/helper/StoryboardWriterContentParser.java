package com.aid.rps.helper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import cn.hutool.core.util.StrUtil;

/**
 * 解析专业版分镜的十字段内容对象。
 *
 * @author 视觉AID
 */
public final class StoryboardWriterContentParser
{
    private static final List<String> REQUIRED_FIELDS = List.of(
            "镜头组", "剧本内容", "画面说明", "台词", "时空环境",
            "引用信息", "镜头模式", "运镜等级", "时长估算", "镜头脚本");

    private StoryboardWriterContentParser()
    {
    }

    /**
     * 返回专业版分镜固定字段。
     *
     * @return 固定字段列表
     */
    public static List<String> requiredFields()
    {
        return REQUIRED_FIELDS;
    }

    /**
     * 解析当前键值对象，并兼容已经生成的旧字符串数组。
     *
     * @param content content 节点
     * @return 合法字段；结构不合法时返回空 Map
     */
    public static Map<String, String> parse(JsonNode content)
    {
        if (Objects.isNull(content))
        {
            return Map.of();
        }
        if (content.isObject())
        {
            return parseObject(content);
        }
        if (content.isArray())
        {
            return parseLegacyArray(content);
        }
        return Map.of();
    }

    private static Map<String, String> parseObject(JsonNode content)
    {
        if (content.size() != REQUIRED_FIELDS.size())
        {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : REQUIRED_FIELDS)
        {
            JsonNode value = content.get(field);
            if (Objects.isNull(value) || !value.isTextual())
            {
                return Map.of();
            }
            fields.put(field, StrUtil.trim(value.asText("")));
        }
        return fields;
    }

    private static Map<String, String> parseLegacyArray(JsonNode content)
    {
        if (content.size() != REQUIRED_FIELDS.size())
        {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < REQUIRED_FIELDS.size(); index++)
        {
            JsonNode lineNode = content.get(index);
            if (Objects.isNull(lineNode) || !lineNode.isTextual())
            {
                return Map.of();
            }
            String line = StrUtil.trim(lineNode.asText(""));
            int separator = line.indexOf('：');
            if (separator < 0)
            {
                separator = line.indexOf(':');
            }
            String expectedField = REQUIRED_FIELDS.get(index);
            if (separator <= 0 || !Objects.equals(expectedField, StrUtil.trim(line.substring(0, separator))))
            {
                return Map.of();
            }
            fields.put(expectedField, StrUtil.trim(line.substring(separator + 1)));
        }
        return fields;
    }
}
