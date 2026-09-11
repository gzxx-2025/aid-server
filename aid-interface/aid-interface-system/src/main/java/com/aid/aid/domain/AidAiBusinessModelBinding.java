package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 业务功能允许使用的模型能力及默认参数。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_ai_business_model_binding")
public class AidAiBusinessModelBinding extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String funcCode;
    private Long modelId;
    private String capabilityCode;
    private Boolean defaultCapability;
    private String defaultsJson;
    private Integer sortOrder;
}
