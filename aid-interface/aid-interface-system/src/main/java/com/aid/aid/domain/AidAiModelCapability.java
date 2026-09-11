package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模型能力的持久化定义。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_ai_model_capability")
public class AidAiModelCapability extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String capabilityCode;
    private String generateMode;
    private String definitionJson;
    private Integer sortOrder;
}
