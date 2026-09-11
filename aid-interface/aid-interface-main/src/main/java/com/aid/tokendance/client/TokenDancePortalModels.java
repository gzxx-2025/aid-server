package com.aid.tokendance.client;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** TokenDance 官方账户接口内部模型。 */
public final class TokenDancePortalModels {
    private TokenDancePortalModels() {
    }

    @Data
    public static class Balance {
        private BigDecimal creditsMicros;
        private BigDecimal creditsUsedMicros;
        private BigDecimal balanceMicros;
    }

    @Data
    public static class PaymentSession {
        private String sessionId;
        private Integer amount;
        private String status;
        private String paymentUrl;
        private String alipayUrl;
        private String statusUrl;
        private Date expiredAt;
        private Date createdAt;
        private Date paidAt;
    }
}
