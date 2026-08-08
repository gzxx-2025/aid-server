package com.aid.aid.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.util.StrUtil;

/**
 * 隐藏风格提示词 JSON 工具。
 *
 * <p>存储协议固定为 character / scene / prop 三个英文键，值必须为字符串。</p>
 */
public final class HiddenStylePromptJsonUtils
{
    public static final String KEY_CHARACTER = "character";

    public static final String KEY_SCENE = "scene";

    public static final String KEY_PROP = "prop";

    private static final Set<String> ALLOWED_KEYS = Set.of(KEY_CHARACTER, KEY_SCENE, KEY_PROP);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HiddenStylePromptJsonUtils()
    {
    }

    /**
     * 校验并规范化隐藏风格 JSON。空值保持为 null，非空时补齐三个固定键。
     *
     * @param json 原始 JSON
     * @return 规范化 JSON，空输入返回 null
     */
    public static String normalize(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (Objects.isNull(root) || !root.isObject())
            {
                throw new IllegalArgumentException("隐藏风格必须是对象");
            }
            root.fieldNames().forEachRemaining(key -> {
                if (!ALLOWED_KEYS.contains(key))
                {
                    throw new IllegalArgumentException("隐藏风格包含未知字段");
                }
            });
            Map<String, String> normalized = new LinkedHashMap<>();
            normalized.put(KEY_CHARACTER, readTextValue(root, KEY_CHARACTER));
            normalized.put(KEY_SCENE, readTextValue(root, KEY_SCENE));
            normalized.put(KEY_PROP, readTextValue(root, KEY_PROP));
            return OBJECT_MAPPER.writeValueAsString(normalized);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("隐藏风格格式错误", e);
        }
    }

    /**
     * 按用户公开提示词生成一期自定义风格隐藏模板。
     */
    public static String fromCharacterPrompt(String promptText)
    {
        return withCharacterPrompt(null, promptText);
    }

    /**
     * 同步角色提示词并保留现有场景、道具模板。
     */
    public static String withCharacterPrompt(String json, String promptText)
    {
        String scenePrompt = "";
        String propPrompt = "";
        if (StrUtil.isNotBlank(json))
        {
            String normalized = normalize(json);
            try
            {
                JsonNode root = OBJECT_MAPPER.readTree(normalized);
                scenePrompt = readTextValue(root, KEY_SCENE);
                propPrompt = readTextValue(root, KEY_PROP);
            }
            catch (Exception e)
            {
                throw new IllegalArgumentException("隐藏风格格式错误", e);
            }
        }
        Map<String, String> promptMap = new LinkedHashMap<>();
        promptMap.put(KEY_CHARACTER, StrUtil.nullToEmpty(promptText));
        promptMap.put(KEY_SCENE, scenePrompt);
        promptMap.put(KEY_PROP, propPrompt);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(promptMap);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("隐藏风格生成失败", e);
        }
    }

    /**
     * 容错读取某一类隐藏提示词；JSON 缺失、非法或目标值为空时返回 fallback。
     */
    public static String resolve(String json, String key, String fallback)
    {
        if (StrUtil.isBlank(json) || !ALLOWED_KEYS.contains(key))
        {
            return StrUtil.nullToEmpty(fallback);
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (Objects.isNull(root) || !root.isObject())
            {
                return StrUtil.nullToEmpty(fallback);
            }
            JsonNode value = root.get(key);
            if (Objects.isNull(value) || !value.isTextual() || StrUtil.isBlank(value.asText()))
            {
                return StrUtil.nullToEmpty(fallback);
            }
            return value.asText();
        }
        catch (Exception e)
        {
            return StrUtil.nullToEmpty(fallback);
        }
    }

    private static String readTextValue(JsonNode root, String key)
    {
        JsonNode value = root.get(key);
        if (Objects.isNull(value) || value.isNull())
        {
            return "";
        }
        if (!value.isTextual())
        {
            throw new IllegalArgumentException("隐藏风格值必须是字符串");
        }
        return value.asText();
    }
}
