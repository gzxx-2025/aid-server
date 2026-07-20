package com.aid.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感词校验注解。
 *
 * @author 视觉AID
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = SensitiveWordValidator.class)
public @interface SensitiveWordCheck {

    /**
     * 校验失败时的错误消息
     * 支持 {fieldName} 占位符，会被替换为实际的字段名称
     */
    String message() default "内容包含敏感词";

    /**
     * 字段名称，用于错误提示
     * 如果不设置，则使用字段本身的名称
     */
    String fieldName() default "";

    /**
     * 是否进行校验
     * 可以通过SpEL表达式动态控制是否校验
     */
    boolean enabled() default true;

    /**
     * 分组
     */
    Class<?>[] groups() default {};

    /**
     * 负载
     */
    Class<? extends Payload>[] payload() default {};
}
