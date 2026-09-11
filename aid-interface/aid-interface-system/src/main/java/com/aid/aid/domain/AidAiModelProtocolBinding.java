package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模型能力的协议绑定记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_ai_model_protocol_binding")
public class AidAiModelProtocolBinding extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long modelId;
    private String capabilityCode;
    private String bindingCode;
    private String protocol;
    private String definitionJson;
    private Integer sortOrder;
}
