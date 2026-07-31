package com.aid.framework.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.aid.crypto.core.CryptoKeyHolder;
import com.aid.common.aid.crypto.core.EncryptedResponse;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;
import com.aid.common.aid.crypto.exception.ApiCryptoException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 响应体加密 Advice。
 *
 * <p>在响应写出前介入：若拦截器标记本请求需要加密（{@link ApiCryptoInterceptor#ATTR_ENCRYPT}=true）
 * 且持有本次 AES 密钥，则把原响应对象（通常是 {@code AjaxResult}）序列化为 JSON，
 * 用 AES-GCM 加密（可选 GZIP），返回统一的 {@link EncryptedResponse} 包体。</p>
 *
 * <p>注意：实现 {@link ResponseBodyAdvice} 必须放在 {@code @ControllerAdvice} Bean 上才会被
 * Spring MVC 识别，因此本类直接标注 {@code @ControllerAdvice}（由
 * {@code @ConditionalOnProperty} 控制仅在加密开启时装配）。</p>
 *
 * @author AID
 */
@ControllerAdvice
public class ApiEncryptResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(ApiEncryptResponseBodyAdvice.class);

    private final EnvelopeCryptoTemplate cryptoTemplate;
    private final ObjectMapper objectMapper;

    public ApiEncryptResponseBodyAdvice(EnvelopeCryptoTemplate cryptoTemplate, ObjectMapper objectMapper) {
        this.cryptoTemplate = cryptoTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅对 JSON 类转换器生效，避免干扰 SSE / 文件流 / 字符串等其它响应。
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 只处理 Jackson JSON 转换器输出的响应
        return org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter.class
                .isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        HttpServletRequest servletRequest = servletRequest(request);
        // 拦截器未标记加密 → 原样返回
        if (servletRequest == null
                || !Boolean.TRUE.equals(servletRequest.getAttribute(ApiCryptoInterceptor.ATTR_ENCRYPT))) {
            return body;
        }
        byte[] aesKey = CryptoKeyHolder.getAesKey();
        // 无密钥（如解密前已抛错的错误响应）→ 明文返回，保证前端可读
        if (aesKey == null) {
            return body;
        }
        // 已经是加密包体（极少数二次进入）→ 不重复加密
        if (body instanceof EncryptedResponse) {
            return body;
        }
        try {
            // 1. 把响应对象序列化为 JSON 字节
            byte[] plain = (body == null)
                    ? new byte[0]
                    : objectMapper.writeValueAsBytes(body);
            // 2. AES-GCM 加密（按需 GZIP）
            EnvelopeCryptoTemplate.EncryptedResult result = cryptoTemplate.encryptResponse(plain, aesKey);
            // 3. 统一返回加密包体；content-type 保持 application/json
            return new EncryptedResponse(result.ivBase64(), result.gzip(), result.bodyBase64());
        } catch (ApiCryptoException e) {
            // 已是业务级异常，直接上抛由全局异常处理器转友好文案
            throw e;
        } catch (Exception e) {
            log.error("响应加密失败: uri={}", servletRequest.getRequestURI(), e);
            throw new ApiCryptoException("加密失败", e);
        }
    }

    /**
     * 提取底层 HttpServletRequest。
     */
    private HttpServletRequest servletRequest(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return servlet.getServletRequest();
        }
        return null;
    }
}
