package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 旧模型标识到统一模型和能力的兼容映射。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_ai_model_alias")
public class AidAiModelAlias extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long legacyModelId;
    private String legacyModelCode;
    private Long modelId;
    private String capabilityCode;
    private String bindingCode;
}
