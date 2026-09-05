package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/** 一次独立Skill执行。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_skill_run")
public class AidSkillRun extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long skillId;
    private Long skillVersionId;
    private String skillConfigHash;
    private String modelCode;
    private Long projectId;
    private Long episodeId;
    private String invokeSource;
    private String clientRequestId;
    private String idempotencyScopeHash;
    private Integer generation;
    private String clientRequestDigest;
    private String executionSnapshotDigest;
    private String resolvedConfigDigest;
    private Long rootRunId;
    private Long parentRunId;
    private String status;
    private String stage;
    private String actionMode;
    private String qualityMode;
    private String inputJson;
    private String outputJson;
    private Boolean effectiveReasoningEnabled;
    private String effectiveReasoningLevel;
    private Boolean showReasoning;
    private Integer reasoningBudgetTokens;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
    private String delFlag;
}
