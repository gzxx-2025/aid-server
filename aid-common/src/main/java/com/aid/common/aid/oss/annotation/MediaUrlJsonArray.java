package com.aid.common.aid.oss.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aid.common.aid.oss.serializer.MediaUrlJsonArrayDeserializer;
import com.aid.common.aid.oss.serializer.MediaUrlJsonArraySerializer;

/**
 * 以「JSON数组字符串」形式存储多媒体URL列表的字段标记。
 * DB 存储示例：<code>["/2026/04/23/a.png","/2026/04/23/b.png"]</code>
 * 序列化给前端时自动为每个元素拼接 CDN/localDomain；
 * 反序列化时每个元素剥离域名，写回 DB。
 *
 * @author 视觉AID
 */
@JacksonAnnotationsInside
@JsonSerialize(using = MediaUrlJsonArraySerializer.class)
@JsonDeserialize(using = MediaUrlJsonArrayDeserializer.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface MediaUrlJsonArray
{
}
