package com.aid.common.aid.crypto.core;

import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;

import com.aid.common.aid.crypto.annotation.ApiDecrypt;
import com.aid.common.aid.crypto.annotation.ApiEncrypt;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.aid.crypto.config.CryptoPathProperties;

import java.lang.reflect.Method;

/**
 * 加解密策略判定器。
 *
 * @author 视觉AID
 */
public class CryptoPolicyResolver {

    private final ApiCryptoConfigProvider configProvider;
    private final CryptoPathProperties pathProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public CryptoPolicyResolver(ApiCryptoConfigProvider configProvider, CryptoPathProperties pathProperties) {
        this.configProvider = configProvider;
        this.pathProperties = pathProperties;
    }

    /**
     * 全局开关是否开启（读 aid_config 动态配置）。
     */
    public boolean isEnabled() {
        return configProvider.getConfig().isEnabled();
    }

    /**
     * 是否需要“解密请求体”。
     *
     * @param uri     请求路径
     * @param handler 处理器（可能为 null，如静态资源或未匹配到 HandlerMethod 时）
     */
    public boolean needDecrypt(String uri, Object handler) {
        if (!isEnabled()) {
            return false;
        }
        HandlerMethod hm = asHandlerMethod(handler);
        if (hm != null && hasAnnotation(hm, CryptoIgnore.class)) {
            return false;
        }
        if (hm != null && hasAnnotation(hm, ApiDecrypt.class)) {
            return true;
        }
        return matchPath(uri);
    }

    /**
     * 是否需要“加密响应体”。
     *
     * @param uri     请求路径
     * @param handler 处理器（可能为 null）
     */
    public boolean needEncrypt(String uri, Object handler) {
        if (!isEnabled()) {
            return false;
        }
        HandlerMethod hm = asHandlerMethod(handler);
        if (hm != null && hasAnnotation(hm, CryptoIgnore.class)) {
            return false;
        }
        if (hm != null && hasAnnotation(hm, ApiEncrypt.class)) {
            return true;
        }
        return matchPath(uri);
    }

    /**
     * 路径是否命中加密策略：先看 exclude（豁免），再看 include。
     */
    public boolean matchPath(String uri) {
        // exclude 优先豁免
        for (String pattern : pathProperties.getExcludePatterns()) {
            if (pathMatcher.match(pattern, uri)) {
                return false;
            }
        }
        // include 命中则加密
        for (String pattern : pathProperties.getIncludePatterns()) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 handler 转为 HandlerMethod，非 HandlerMethod 返回 null。
     */
    private HandlerMethod asHandlerMethod(Object handler) {
        return (handler instanceof HandlerMethod) ? (HandlerMethod) handler : null;
    }

    /**
     * 方法或其所在类是否标注了指定注解。
     */
    private <A extends java.lang.annotation.Annotation> boolean hasAnnotation(HandlerMethod hm, Class<A> annotation) {
        Method method = hm.getMethod();
        if (method.isAnnotationPresent(annotation)) {
            return true;
        }
        return hm.getBeanType().isAnnotationPresent(annotation);
    }
}
