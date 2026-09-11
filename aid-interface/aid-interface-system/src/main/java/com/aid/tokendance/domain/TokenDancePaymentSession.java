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

/** TokenDance 管理员充值会话。 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "requestHash")
@TableName("aid_tokendance_payment_session")
public class TokenDancePaymentSession extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private Integer credentialVersion;
    private Long adminUserId;
    @JsonIgnore
    private String requestHash;
    private String upstreamSessionId;
    private Integer amount;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date userConfirmedAt;
    private String paymentUrl;
    private String alipayUrl;
    private String statusUrl;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date upstreamCreatedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expiredAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date paidAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastQueryTime;
    private String balanceRefreshStatus;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date balanceRefreshedAt;
    private String lastErrorCode;
}
