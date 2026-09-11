package com.aid.project.snapshot.domain;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 发布快照媒体引用索引。 */
@Data
@TableName("aid_project_publish_snapshot_media")
public class AidProjectPublishSnapshotMedia implements Serializable
{
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private String mediaUrlHash;
    private String mediaUrl;
}
