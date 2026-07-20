package com.aid.common.aid.oss.serializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.utils.spring.SpringUtils;

import cn.hutool.core.util.StrUtil;

/**
 * JSON 数组字符串形式的媒体URL列表反序列化器
 * 前端提交的 JSON 数组内每个元素剥离 CDN/localDomain，写入 DB 时只保留相对路径。
 *
 * @author 视觉AID
 */
public class MediaUrlJsonArrayDeserializer extends JsonDeserializer<String>
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile OssConfigManager cachedManager;

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        String value = p.getValueAsString();
        if (StrUtil.isBlank(value))
        {
            return value;
        }
        OssConfigManager manager = resolveManager();
        if (Objects.isNull(manager))
        {
            return value;
        }
        OssProperties properties = manager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return value;
        }
        try
        {
            JsonNode node = MAPPER.readTree(value);
            if (!node.isArray())
            {
                return stripDomain(value, properties);
            }
            List<String> stripped = new ArrayList<>();
            for (JsonNode item : node)
            {
                stripped.add(stripDomain(item.asText(""), properties));
            }
            return MAPPER.writeValueAsString(stripped);
        }
        catch (Exception ex)
        {
            return value;
        }
    }

    /**
     * 剥离配置的 CDN/localDomain 前缀，保留相对路径
     */
    private String stripDomain(String url, OssProperties properties)
    {
        if (StrUtil.isBlank(url))
        {
            return url;
        }
        String cdn = trim(properties.getCdnDomain());
        if (StrUtil.isNotBlank(cdn) && url.startsWith(cdn))
        {
            String rest = url.substring(cdn.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        String local = trim(properties.getLocalDomain());
        if (StrUtil.isNotBlank(local) && url.startsWith(local))
        {
            String rest = url.substring(local.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        return url;
    }

    private String trim(String d)
    {
        if (StrUtil.isBlank(d))
        {
            return d;
        }
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    private OssConfigManager resolveManager()
    {
        if (Objects.nonNull(cachedManager))
        {
            return cachedManager;
        }
        try
        {
            cachedManager = SpringUtils.getBean(OssConfigManager.class);
        }
        catch (Exception ignore)
        {
            // ignore
        }
        return cachedManager;
    }
}
