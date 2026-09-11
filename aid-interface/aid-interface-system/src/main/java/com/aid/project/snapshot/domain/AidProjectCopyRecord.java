package com.aid.project.snapshot.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 项目复制幂等记录。 */
@Data
@TableName("aid_project_copy_record")
public class AidProjectCopyRecord implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String requestId;
    private Long sourceProjectId;
    private Long sourceSnapshotId;
    private Long targetProjectId;
    private Date createTime;
}
