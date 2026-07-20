package com.aid.framework.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.crypto.config.CryptoPathProperties;
import com.aid.common.aid.crypto.core.ApiCryptoConfigProvider;
import com.aid.common.aid.crypto.core.CryptoPolicyResolver;
import com.aid.common.aid.crypto.core.EnvelopeCryptoTemplate;

/**
 * 接口信封加密核心 Bean 装配。
 *
 * <p>开关（enabled）与公私钥等参数运行期从数据库 {@code aid_config}（category={@code api_crypto}）读取，
 * 故核心 Bean 恒定装配，由 {@link CryptoPolicyResolver} 在每次请求按数据库配置判定是否加解密。</p>
 *
 * <p>放在框架模块（而非 common 自动配置）装配，确保 {@link ConfigService} 实现
 * （{@code AidConfigServiceImpl}，组件扫描）已可注入；路径策略 {@link CryptoPathProperties} 走 yml。</p>
 *
 * @author AID
 */
@Configuration
@EnableConfigurationProperties(CryptoPathProperties.class)
public class ApiCryptoBeanConfig {

    /**
     * 加密配置提供者（从 aid_config 动态加载 + 30s 缓存）。
     */
    @Bean
    public ApiCryptoConfigProvider apiCryptoConfigProvider(ConfigService configService) {
        return new ApiCryptoConfigProvider(configService);
    }

    /**
     * 加解密编排模板。
     */
    @Bean
    public EnvelopeCryptoTemplate envelopeCryptoTemplate(ApiCryptoConfigProvider provider) {
        return new EnvelopeCryptoTemplate(provider);
    }

    /**
     * 加解密策略判定器。
     */
    @Bean
    public CryptoPolicyResolver cryptoPolicyResolver(ApiCryptoConfigProvider provider,
                                                     CryptoPathProperties pathProperties) {
        return new CryptoPolicyResolver(provider, pathProperties);
    }
}
