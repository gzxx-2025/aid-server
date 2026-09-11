package com.aid.tokendance.client;

import lombok.Getter;

/** TokenDance 账户接口安全异常。 */
@Getter
public class TokenDancePortalException extends RuntimeException {
    private final String errorCode;
    private final boolean outcomeUncertain;

    public TokenDancePortalException(String message, String errorCode, boolean outcomeUncertain) {
        super(message);
        this.errorCode = errorCode;
        this.outcomeUncertain = outcomeUncertain;
    }
}
