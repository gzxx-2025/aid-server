package com.aid.tokendance.credential;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.ToString;

/** TokenDance 服务端调用所需的短生命周期凭证。 */
@Getter
@ToString(exclude = "apiKey")
public class ResolvedTokenDanceCredential {
    private final Long providerId;
    private final Integer credentialVersion;
    @JsonIgnore
    private final String apiKey;

    public ResolvedTokenDanceCredential(Long providerId, Integer credentialVersion, String apiKey) {
        this.providerId = providerId;
        this.credentialVersion = credentialVersion;
        this.apiKey = apiKey;
    }
}
