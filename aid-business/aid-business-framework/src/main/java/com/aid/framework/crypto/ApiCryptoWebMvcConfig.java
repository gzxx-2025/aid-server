package com.aid.framework.crypto;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aid.common.aid.crypto.core.CryptoPolicyResolver;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;

/**
 * 接口加解密 Web 层装配。
 *
 * <p>注册 {@link ApiCryptoInterceptor} 到 {@code /**}，内部按数据库 aid_config 动态配置判定是否处理；
 * 请求体解密 / 响应体加密分别由 {@link ApiDecryptRequestBodyAdvice} /
 * {@link ApiEncryptResponseBodyAdvice}（{@code @ControllerAdvice}）完成。</p>
 *
 * <p>恒定装配：开关在运行期由 {@link CryptoPolicyResolver} 读 aid_config 判定，
 * 关闭时拦截器直接放行，开销极小。</p>
 *
 * @author AID
 */
@Configuration
public class ApiCryptoWebMvcConfig implements WebMvcConfigurer {

    private final CryptoPolicyResolver policyResolver;
    private final EnvelopeCryptoTemplate cryptoTemplate;

    public ApiCryptoWebMvcConfig(CryptoPolicyResolver policyResolver,
                                 EnvelopeCryptoTemplate cryptoTemplate) {
        this.policyResolver = policyResolver;
        this.cryptoTemplate = cryptoTemplate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order 设小值，确保早于业务拦截器执行（先解出密钥，再进入后续链路）
        registry.addInterceptor(new ApiCryptoInterceptor(policyResolver, cryptoTemplate))
                .addPathPatterns("/**")
                .order(-100);
    }
}
