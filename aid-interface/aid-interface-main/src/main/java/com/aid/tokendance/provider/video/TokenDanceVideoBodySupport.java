package com.aid.tokendance.provider.video;

import cn.hutool.core.util.StrUtil;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import lombok.extern.slf4j.Slf4j;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TokenDance 视频方言共用的无损结构构造工具。 */
@Slf4j
final class TokenDanceVideoBodySupport
{
    private TokenDanceVideoBodySupport()
    {
    }

    static Map<String, Object> content(String type, String text, String url, String role, String id)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        putIfPresent(item, "text", text);
        putIfPresent(item, "url", url);
        putIfPresent(item, "role", role);
        putIfPresent(item, "id", id);
        return item;
    }

    static Map<String, Object> typedUrl(String type, String url)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("url", url);
        return item;
    }

    static Map<String, Object> nestedMedia(String type, String url, String role)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put(type, Map.of("url", url));
        putIfPresent(item, "role", role);
        return item;
    }

    static void putIfPresent(Map<String, Object> target, String key, Object value)
    {
        if (value instanceof String text)
        {
            String normalized = StrUtil.trimToNull(text);
            if (normalized != null)
            {
                target.put(key, normalized);
            }
            return;
        }
        if (value != null)
        {
            target.put(key, value);
        }
    }

    static void require(boolean condition, String message)
    {
        if (!condition)
        {
            log.info("TokenDance 视频请求校验失败: {}", message);
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, message);
        }
    }

    static void reject(boolean condition, String message)
    {
        require(!condition, message);
    }

    static String lowerResolution(String value)
    {
        String normalized = StrUtil.trimToNull(value);
        if (normalized == null)
        {
            return null;
        }
        return normalized.replace('×', 'x').replace('*', 'x').toLowerCase(Locale.ROOT);
    }

    static String upperResolution(String value)
    {
        String normalized = StrUtil.trimToNull(value);
        if (normalized == null)
        {
            return null;
        }
        return normalized.replace('×', 'x').replace('*', 'x').toUpperCase(Locale.ROOT);
    }

    static Map<String, Object> watermarkOptions(TokenDanceVideoInputs inputs)
    {
        Map<String, Object> options = new LinkedHashMap<>();
        Object configured = inputs.options.get("watermark_info");
        if (configured instanceof Map<?, ?> raw)
        {
            Map<String, Object> watermark = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet())
            {
                if (entry.getKey() != null && entry.getValue() != null)
                {
                    watermark.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            if (!watermark.isEmpty())
            {
                options.put("watermark_info", watermark);
            }
        }
        else if (inputs.watermark != null)
        {
            options.put("watermark_info", Map.of("enabled", inputs.watermark));
        }
        return options;
    }

    static boolean hasAny(List<?>... lists)
    {
        if (lists != null)
        {
            for (List<?> list : lists)
            {
                if (list != null && !list.isEmpty())
                {
                    return true;
                }
            }
        }
        return false;
    }

    static Boolean optionBoolean(TokenDanceVideoInputs inputs, String key)
    {
        return TokenDancePayloadSupport.bool(inputs.options, key);
    }
}
