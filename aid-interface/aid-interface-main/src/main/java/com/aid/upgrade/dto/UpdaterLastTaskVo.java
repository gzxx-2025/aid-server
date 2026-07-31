package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 升级器最近一次任务执行结果
 *
 * @author 视觉AID
 */
@Data
public class UpdaterLastTaskVo {

    /** 任务ID */
    private String taskId;

    /** 任务动作：UPGRADE/UPDATER_UPGRADE/ROLLBACK */
    private String action;

    /** 任务状态：RUNNING/SUCCESS/FAILED */
    private String state;

    /** 结果说明 */
    private String message;

    /** 完成时间 */
    private String finishedAt;
}
