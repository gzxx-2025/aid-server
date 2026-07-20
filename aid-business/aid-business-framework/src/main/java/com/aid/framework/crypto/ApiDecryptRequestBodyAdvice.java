package com.aid.framework.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import com.aid.common.aid.crypto.core.CryptoKeyHolder;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;
import com.aid.common.aid.crypto.exception.ApiCryptoException;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求体解密 Advice。
 *
 * <p>在 Jackson 反序列化 {@code @RequestBody} 之前介入：若拦截器已标记本请求需要解密
 * （{@link ApiCryptoInterceptor#ATTR_DECRYPT}=true），则读取加密请求体（Base64 密文），
 * 用本次 AES 密钥 + IV 解密为明文 JSON 字节流，替换原始输入流交给后续转换器，
 * 业务 Controller 完全无感知。</p>
 *
 * @author AID
 */
@ControllerAdvice
public class ApiDecryptRequestBodyAdvice extends RequestBodyAdviceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ApiDecryptRequestBodyAdvice.class);

    private final EnvelopeCryptoTemplate cryptoTemplate;

    public ApiDecryptRequestBodyAdvice(EnvelopeCryptoTemplate cryptoTemplate) {
        this.cryptoTemplate = cryptoTemplate;
    }

    /**
     * 是否支持：恒返回 true，真正是否解密在 {@link #beforeBodyRead} 内按请求属性判断。
     */
    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        HttpServletRequest request = currentRequest();
        // 拦截器未标记需要解密 → 原样返回
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(ApiCryptoInterceptor.ATTR_DECRYPT))) {
            return inputMessage;
        }
        // 读取加密请求体（Base64 密文文本）
        byte[] rawBytes = inputMessage.getBody().readAllBytes();
        String bodyBase64 = new String(rawBytes, StandardCharsets.UTF_8).trim();
        // 空体（如无参 POST）：放行空流，交给后续转换器/校验处理
        if (StrUtil.isBlank(bodyBase64)) {
            return wrap(inputMessage.getHeaders(), new byte[0]);
        }
        byte[] aesKey = CryptoKeyHolder.getAesKey();
        if (aesKey == null) {
            // 理论上拦截器已保证有密钥；防御性兜底
            log.error("请求解密失败: ThreadLocal 中无 AES 密钥, uri={}", request.getRequestURI());
            throw new ApiCryptoException("缺少密钥");
        }
        String iv = request.getHeader(ApiCryptoInterceptor.HEADER_IV);
        byte[] plain = cryptoTemplate.decryptRequestBody(bodyBase64, aesKey, iv);
        // 用解密后的明文 JSON 字节替换输入流
        return wrap(inputMessage.getHeaders(), plain);
    }

    /**
     * 包装为新的 HttpInputMessage（替换 body，保留原始 headers）。
     */
    private HttpInputMessage wrap(HttpHeaders headers, byte[] body) {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    /**
     * 获取当前请求对象。
     */
    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}
