package com.aid.framework.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import com.aid.common.aid.crypto.core.CryptoKeyHolder;
import com.aid.common.aid.crypto.core.CryptoPolicyResolver;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;
import com.aid.common.aid.crypto.exception.ApiCryptoException;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 接口加解密前置拦截器。
 *
 * <p>职责（在 {@code preHandle} 完成，先于 RequestBodyAdvice 执行）：</p>
 * <ol>
 *   <li>依据策略判定本请求是否需要解密请求 / 加密响应；都不需要则直接放行；</li>
 *   <li>校验时间戳 {@code X-Encrypt-Ts}（防重放，窗口可配；窗口为 0 时关闭）；</li>
 *   <li>从 {@code X-Encrypt-Key} 解出一次性 AES 密钥，存入 {@link CryptoKeyHolder}；</li>
 *   <li>将解密 / 加密决策写入请求属性，供后续 RequestBodyAdvice / ResponseBodyAdvice 读取。</li>
 * </ol>
 *
 * <p>密钥获取失败时抛 {@link ApiCryptoException}，此时请求属性未设置，错误响应将以明文返回，
 * 保证前端能读到可读错误信息（不会陷入“连错误都被加密但又无密钥”的死结）。</p>
 *
 * @author AID
 */
public class ApiCryptoInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiCryptoInterceptor.class);

    /** 请求头：RSA 加密后的一次性 AES 密钥（Base64） */
    public static final String HEADER_KEY = "X-Encrypt-Key";

    /** 请求头：AES-GCM IV（Base64） */
    public static final String HEADER_IV = "X-Encrypt-Iv";

    /** 请求头：客户端时间戳（毫秒） */
    public static final String HEADER_TS = "X-Encrypt-Ts";

    /** 请求属性：是否需要解密请求体 */
    public static final String ATTR_DECRYPT = "AID_CRYPTO_DECRYPT_REQUEST";

    /** 请求属性：是否需要加密响应体 */
    public static final String ATTR_ENCRYPT = "AID_CRYPTO_ENCRYPT_RESPONSE";

    private final CryptoPolicyResolver policyResolver;
    private final EnvelopeCryptoTemplate cryptoTemplate;

    public ApiCryptoInterceptor(CryptoPolicyResolver policyResolver,
                                EnvelopeCryptoTemplate cryptoTemplate) {
        this.policyResolver = policyResolver;
        this.cryptoTemplate = cryptoTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        boolean needDecrypt = policyResolver.needDecrypt(uri, handler);
        boolean needEncrypt = policyResolver.needEncrypt(uri, handler);
        // 两者都不需要：与加密无关的请求，直接放行
        if (!needDecrypt && !needEncrypt) {
            return true;
        }
        // 1. 时间戳防重放校验
        verifyTimestamp(request, uri);
        // 2. 解出一次性 AES 密钥并暂存（供请求解密 + 响应加密复用）
        String encryptedKey = request.getHeader(HEADER_KEY);
        byte[] aesKey = cryptoTemplate.decryptAesKey(encryptedKey);
        CryptoKeyHolder.setAesKey(aesKey);
        // 3. 成功拿到密钥后才落决策属性；失败已抛异常，错误响应保持明文
        request.setAttribute(ATTR_DECRYPT, needDecrypt);
        request.setAttribute(ATTR_ENCRYPT, needEncrypt);
        return true;
    }

    /**
     * 校验时间戳是否在允许窗口内。
     *
     * @param request 请求
     * @param uri     请求路径（仅用于日志）
     */
    private void verifyTimestamp(HttpServletRequest request, String uri) {
        long window = cryptoTemplate.getTimestampWindowMs();
        // 窗口 <= 0 表示关闭时间戳校验
        if (window <= 0) {
            return;
        }
        String ts = request.getHeader(HEADER_TS);
        if (StrUtil.isBlank(ts)) {
            log.error("接口解密失败: 缺少时间戳头 X-Encrypt-Ts, uri={}", uri);
            throw new ApiCryptoException("缺少时间戳");
        }
        long clientTs;
        try {
            clientTs = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            log.error("接口解密失败: 时间戳格式非法, ts={}, uri={}", ts, uri);
            throw new ApiCryptoException("时间戳非法");
        }
        long diff = Math.abs(System.currentTimeMillis() - clientTs);
        // 超出窗口判定为重放/时钟异常
        if (diff > window) {
            log.error("接口解密失败: 时间戳超出窗口, diff={}ms, window={}ms, uri={}", diff, window, uri);
            throw new ApiCryptoException("请求已过期");
        }
    }

    /**
     * 请求结束统一清理 ThreadLocal，防止线程复用导致密钥串号。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CryptoKeyHolder.clear();
    }
}
