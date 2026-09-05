package com.aid.skill.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 持久化运行里程碑；主键 ID 同时作为单调事件序号。 */
@Data
@TableName("aid_skill_run_event")
public class AidSkillRunEvent implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long runId;
    /** 阶段与终态等唯一事件的数据库门禁键；增量事件为空。 */
    private String eventKey;
    private String eventType;
    private String stage;
    private Long stepId;
    private Long mediaTaskId;
    private String payloadJson;
    private Date createTime;
}
