package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** SEO 提交审计日志，只保存脱敏后的上游摘要。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_seo_submission_log")
public class AidSeoSubmissionLog extends BaseEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private Long pageId;
    private String provider;
    private String channel;
    private String triggerType;
    private String submitStatus;
    private String urlSnapshot;
    private Integer httpStatus;
    private String responseSummary;
    private String errorCode;
    private String errorMessage;
    private Long operatorId;
    private String operatorName;
    private String delFlag;
}
