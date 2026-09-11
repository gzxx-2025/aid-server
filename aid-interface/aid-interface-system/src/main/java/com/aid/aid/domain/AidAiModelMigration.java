package com.aid.aid.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模型配置迁移的幂等凭据和可恢复记录，不包含任务或账单。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_ai_model_migration")
public class AidAiModelMigration extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestKey;
    private String requestDigest;
    private String status;
    private Integer affectedModels;
    @JsonIgnore private String beforeJson;
    @JsonIgnore private String afterJson;
}
