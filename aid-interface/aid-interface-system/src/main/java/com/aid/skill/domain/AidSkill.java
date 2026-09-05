package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** Skill稳定身份。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_skill")
public class AidSkill extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String skillCode;
    private String name;
    private String description;
    /** 面向调用方的能力说明，由后台维护，不使用代码硬编码回退。 */
    private String capabilityDescription;
    private String iconUrl;
    private String ownerType;
    private Long ownerUserId;
    private String visibility;
    /** ENTRYPOINT 可由外部入口调用，INTERNAL 只能由已授权父 Skill 调用。 */
    private String invocationScope;
    /** 当前不可变可执行版本。 */
    private Long currentVersionId;
    private String executorType;
    private String modelCode;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String systemPrompt;
    private String reasoningPolicy;
    private Boolean defaultReasoningEnabled;
    private String defaultReasoningLevel;
    private Boolean showReasoningDefault;
    private Integer reasoningBudgetTokens;
    private Integer maxOutputTokens;
    private Integer contextWindowTokens;
    private Integer safetyMarginTokens;
    private String definitionJson;
    private String configHash;
    private String status;
    private String delFlag;
}
