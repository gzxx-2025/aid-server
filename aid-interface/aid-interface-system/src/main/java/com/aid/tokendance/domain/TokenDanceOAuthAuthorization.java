package com.aid.tokendance.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Date;

/** TokenDance API Key OAuth 授权过程。 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "verifierValue")
@TableName("aid_tokendance_oauth_authorization")
public class TokenDanceOAuthAuthorization extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String authorizationRef;
    private Long providerId;
    private Long adminUserId;
    private String authorizationMode;
    @JsonIgnore
    private String verifierValue;
    private String callbackUrl;
    private String appUrl;
    private String keyName;
    private String status;
    private Integer credentialVersion;
    private String failureCode;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expiresAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date exchangedAt;
}
