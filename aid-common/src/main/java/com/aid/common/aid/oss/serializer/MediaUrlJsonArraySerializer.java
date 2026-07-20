package com.aid.common.aid.oss.serializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.spring.SpringUtils;

import cn.hutool.core.util.StrUtil;

/**
 * JSON 数组字符串形式的媒体URL列表序列化器
 * 适用于 DB 存 JSON 串（如 extraImages = <code>["/2026/04/23/a.png","/2026/04/23/b.png"]</code>）
 * 的字段。序列化时解析 JSON，逐个元素拼域名；反解析失败则原样输出。
 *
 * @author 视觉AID
 */
public class MediaUrlJsonArraySerializer extends JsonSerializer<String>
{
    /**
     * 共用 ObjectMapper（Jackson 无状态，安全复用）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 缓存 resolver
     */
    private volatile MediaUrlResolver cachedResolver;

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException
    {
        // 空值原样
        if (StrUtil.isBlank(value))
        {
            gen.writeString(value);
            return;
        }
        MediaUrlResolver resolver = resolveResolver();
        if (Objects.isNull(resolver))
        {
            gen.writeString(value);
            return;
        }
        try
        {
            JsonNode node = MAPPER.readTree(value);
            // 非数组：按单值处理
            if (!node.isArray())
            {
                gen.writeString(resolver.toFullUrl(value));
                return;
            }
            List<String> resolved = new ArrayList<>();
            for (JsonNode item : node)
            {
                String url = item.asText("");
                resolved.add(resolver.toFullUrl(url));
            }
            // 重新 JSON 序列化成字符串返回（与 DB 存储格式一致，前端按需解析）
            gen.writeString(MAPPER.writeValueAsString(resolved));
        }
        catch (Exception ex)
        {
            // 解析失败按原值输出，不阻断接口
            gen.writeString(value);
        }
    }

    private MediaUrlResolver resolveResolver()
    {
        if (Objects.nonNull(cachedResolver))
        {
            return cachedResolver;
        }
        try
        {
            cachedResolver = SpringUtils.getBean(MediaUrlResolver.class);
        }
        catch (Exception ignore)
        {
            // ignore
        }
        return cachedResolver;
    }
}
