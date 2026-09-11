package com.aid.project.snapshot.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 审核通过后固化且内容不可变的项目流程版本。 */
@Data
@TableName("aid_project_publish_snapshot")
public class AidProjectPublishSnapshot implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long ownerUserId;
    private Integer revisionNo;
    private Integer schemaVersion;
    private String contentHash;
    private String snapshotJson;
    private Integer nodeCount;
    private Date createTime;
    private String createBy;
}
