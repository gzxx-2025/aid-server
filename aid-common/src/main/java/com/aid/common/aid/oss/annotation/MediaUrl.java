package com.aid.common.aid.oss.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aid.common.aid.oss.serializer.MediaUrlDeserializer;
import com.aid.common.aid.oss.serializer.MediaUrlSerializer;

/**
 * 媒体URL字段标记注解。
 *
 * @author 视觉AID
 */
@JacksonAnnotationsInside
@JsonSerialize(using = MediaUrlSerializer.class)
@JsonDeserialize(using = MediaUrlDeserializer.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface MediaUrl
{
}
