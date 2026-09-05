package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/** 可恢复的结构化澄清请求。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_skill_input_request", excludeProperty = "remark")
public class AidSkillInputRequest extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String requestKey;
    private Integer roundNo;
    private String status;
    private String schemaDigest;
    private String contextVersion;
    private String acceptedRevision;
    private String questionBundleJson;
    private Date expiresAt;
    private Date answeredAt;
    private String delFlag;
}
