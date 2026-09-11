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

/** TokenDance 服务端凭证版本。完整 Key 不允许序列化或输出到日志。 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"credentialValue", "credentialFingerprint"})
@TableName("aid_tokendance_credential")
public class TokenDanceCredential extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private Integer credentialVersion;
    private Long adminUserId;
    @JsonIgnore
    private String credentialValue;
    @JsonIgnore
    private String credentialFingerprint;
    private String credentialHint;
    private String sourceType;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date authorizedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date retiredAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date revokedAt;
}
