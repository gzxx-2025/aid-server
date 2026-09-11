package com.aid.tokendance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** TokenDance 账户接口部署配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "aid.tokendance.account")
public class TokenDanceAccountProperties {
    private String portalBaseUrl = "https://tokendance.space";
    private int httpTimeoutMillis = 20_000;
    private long balanceCacheMillis = 3_000L;
}
