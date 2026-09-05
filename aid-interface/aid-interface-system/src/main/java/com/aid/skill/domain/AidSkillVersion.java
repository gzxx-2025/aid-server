package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 不可变、可执行的 Skill 版本；列表查询不得加载提示词和 Schema 大字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_skill_version")
public class AidSkillVersion extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private String versionCode;
    private String visibility;
    private String invocationScope;
    private String publishStatus;
    private String executorType;
    private String modelCode;
    /** 不可变版本的默认模型与有序可选模型快照。 */
    private String modelConfigJson;
    private String packageDigest;
    private String manifestJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String systemPrompt;
    private String definitionJson;
    private Integer maxOutputTokens;
    private Integer contextWindowTokens;
    private Integer safetyMarginTokens;
    private String status;
    private String delFlag;
}
