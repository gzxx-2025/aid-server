package com.aid.aid.domain;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 提取任务计费快照对象 aid_extract_task_billing_snapshot
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_extract_task_billing_snapshot")
public class AidExtractTaskBillingSnapshot extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 提取任务ID */
    private Long taskId;

    /** 项目ID */
    private Long projectId;

    /** 剧集ID */
    private Long episodeId;

    /** 用户ID */
    private Long userId;

    /** 任务类型 */
    private String taskType;

    /** 快照阶段：FROZEN/SETTLED */
    private String snapshotStage;

    /** 计费快照JSON */
    private String snapshotJson;

    /** 快照字符长度 */
    private Integer snapshotSize;

    /** 快照SHA256 */
    private String snapshotHash;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}
