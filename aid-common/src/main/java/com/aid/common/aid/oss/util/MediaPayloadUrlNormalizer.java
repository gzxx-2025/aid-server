package com.aid.common.aid.oss.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/**
 * Normalizes media URL fields inside dynamic payloads.
 *
 * @author 视觉AID
 */
@Component
@RequiredArgsConstructor
public class MediaPayloadUrlNormalizer
{
    private static final Set<String> MEDIA_URL_KEYS = Set.of(
            "imageUrl", "cardImageUrl", "videoUrl", "audioUrl", "fileUrl",
            "coverUrl", "posterUrl", "thumbnailUrl", "avatarUrl", "resourceUrl",
            "mediaUrl", "ossUrl", "originUrl", "refImageUrl", "referenceImageUrl");

    private static final Set<String> MEDIA_URL_LIST_KEYS = Set.of(
            "imageUrls", "cardImageUrls", "videoUrls", "audioUrls", "fileUrls",
            "coverUrls", "posterUrls", "thumbnailUrls", "resourceUrls",
            "mediaUrls", "ossUrls", "refImageUrls", "referenceImageUrls", "referenceImages");

    private final MediaUrlResolver mediaUrlResolver;

    /**
     * Returns a copy of the payload with media URL fields converted to full URLs.
     *
     * @param payload dynamic SSE/detail payload
     * @return normalized payload
     */
    public Object normalize(Object payload)
    {
        return normalizeValue(null, payload);
    }

    private Object normalizeValue(String key, Object value)
    {
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((childKey, childValue) ->
            {
                String childKeyText = String.valueOf(childKey);
                result.put(childKeyText, normalizeValue(childKeyText, childValue));
            });
            return result;
        }
        if (value instanceof List<?> list)
        {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list)
            {
                if (item instanceof String text && isMediaUrlListKey(key))
                {
                    result.add(toFullUrl(text));
                }
                else
                {
                    result.add(normalizeValue(null, item));
                }
            }
            return result;
        }
        if (value instanceof String text && isMediaUrlKey(key))
        {
            return toFullUrl(text);
        }
        return value;
    }

    private boolean isMediaUrlKey(String key)
    {
        if (StrUtil.isBlank(key))
        {
            return false;
        }
        if (MEDIA_URL_KEYS.contains(key))
        {
            return true;
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        return lowerKey.endsWith("url") && hasMediaToken(lowerKey);
    }

    private boolean isMediaUrlListKey(String key)
    {
        if (StrUtil.isBlank(key))
        {
            return false;
        }
        if (MEDIA_URL_LIST_KEYS.contains(key))
        {
            return true;
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        return lowerKey.endsWith("urls") && hasMediaToken(lowerKey);
    }

    private boolean hasMediaToken(String lowerKey)
    {
        return lowerKey.contains("image")
                || lowerKey.contains("video")
                || lowerKey.contains("audio")
                || lowerKey.contains("file")
                || lowerKey.contains("cover")
                || lowerKey.contains("poster")
                || lowerKey.contains("thumb")
                || lowerKey.contains("avatar")
                || lowerKey.contains("resource")
                || lowerKey.contains("media")
                || lowerKey.contains("oss")
                || lowerKey.contains("ref");
    }

    private String toFullUrl(String url)
    {
        return StrUtil.isBlank(url) ? url : mediaUrlResolver.toFullUrl(url);
    }
}
