package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 澄清回答；同一响应键只允许保存一次。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_skill_input_response", excludeProperty = "remark")
public class AidSkillInputResponse extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long inputRequestId;
    private Long runId;
    private Long userId;
    private String responseKey;
    private String responseDigest;
    private String answersJson;
    private String delFlag;
}
