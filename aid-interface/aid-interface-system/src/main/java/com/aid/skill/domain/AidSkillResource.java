package com.aid.skill.domain;

import com.aid.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/** 记录不可变 Skill Version 的资源索引和内容摘要。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "aid_skill_resource", excludeProperty = "remark")
public class AidSkillResource extends BaseEntity implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long skillVersionId;
    private String resourceKey;
    private String resourceType;
    private String objectKey;
    private String contentDigest;
    private String mimeType;
    private Long sizeBytes;
    private String routeJson;
    /**
     * 后台发布的小型文本资源。历史 classpath 包表没有该列，因此元数据查询不得隐式读取；
     * 数据库包加载器会显式选择该字段。
     */
    @TableField(select = false)
    private String contentText;
    private String status;
    private String delFlag;
}
