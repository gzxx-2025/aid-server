package com.aid.skill.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** Skill 管理员草稿；草稿不可直接执行。 */
@Data
@TableName("aid_skill_draft")
public class AidSkillDraft implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Long ownerUserId;
    private String activeKey;
    private String draftJson;
    private String draftDigest;
    private String status;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
