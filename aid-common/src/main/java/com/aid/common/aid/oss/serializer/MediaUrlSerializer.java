package com.aid.common.aid.oss.serializer;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.spring.SpringUtils;

/**
 * 媒体URL字段 Jackson 序列化器
 * 将 DB 存储的相对路径在序列化到 JSON 时拼接成完整 URL。
 * 用 {@link SpringUtils#getBean} 延迟拿 {@link MediaUrlResolver}，避免 Jackson 构造期间循环依赖。
 *
 * @author 视觉AID
 */
public class MediaUrlSerializer extends JsonSerializer<String>
{
    /**
     * 缓存 resolver 引用，避免每次序列化都从 Spring 容器拿
     */
    private volatile MediaUrlResolver cachedResolver;

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException
    {
        // 空值原样输出（null → null，空串 → ""）
        if (Objects.isNull(value) || value.isEmpty())
        {
            gen.writeString(value);
            return;
        }
        MediaUrlResolver resolver = resolveResolver();
        if (Objects.isNull(resolver))
        {
            // Spring 未就绪（极少发生），原样输出，避免异常阻塞序列化
            gen.writeString(value);
            return;
        }
        gen.writeString(resolver.toFullUrl(value));
    }

    /**
     * 延迟拿 resolver bean
     */
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
            // Spring 尚未初始化或 bean 不存在，返回 null 走兜底
        }
        return cachedResolver;
    }
}
