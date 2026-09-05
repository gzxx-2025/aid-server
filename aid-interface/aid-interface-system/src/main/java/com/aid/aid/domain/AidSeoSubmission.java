package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/** 单个页面在一个搜索引擎提交渠道中的当前状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_seo_submission")
public class AidSeoSubmission extends BaseEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long pageId;
    private String provider;
    private String channel;
    private String submitStatus;
    private String submittedHash;
    private Integer attemptCount;
    private Date nextRetryTime;
    private Date lastAttemptTime;
    private Date acceptedTime;
    private Integer lastHttpStatus;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Integer providerRemain;
    private String delFlag;
}
