package com.aid.common.captcha.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行为验证码校验注解。
 *
 * 标注在控制器方法上，命中后由 {@code CaptchaInterceptor} 校验请求头携带的二次验证 token。
 * 是否真正校验由 aid_config(category=captcha) 的开关、就绪状态与受保护场景共同决定。
 *
 * @author 视觉AID
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CaptchaRequired {

    /**
     * 业务场景标识（如 login、sendCode），用于与配置中的受保护场景匹配。
     */
    String scene();
}
