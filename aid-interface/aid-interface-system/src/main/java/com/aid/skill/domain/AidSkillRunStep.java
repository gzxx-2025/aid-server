package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** Run 编排步骤；媒体状态、金额和 Provider 结果不在本表复制。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_skill_run_step", excludeProperty = "remark")
public class AidSkillRunStep extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Integer stepSeq;
    private String stepKey;
    private String stepExecutionId;
    private Long skillId;
    private Long skillVersionId;
    private String actionMode;
    private Integer workflowAttempt;
    private String orchestrationStatus;
    private String checkpointJson;
    private String delFlag;
}
