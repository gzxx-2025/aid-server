package com.aid.upgrade.dto;

import java.util.Map;

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

    /** 任务状态：RUNNING/SUCCESS/FAILED/CANCELLED */
    private String state;

    /** 结果说明 */
    private String message;

    /** 当前进度百分比：0-100 */
    private Integer progress;

    /** 当前执行阶段 */
    private String phase;

    /** 开始时间 */
    private String startedAt;

    /** 最近进度更新时间 */
    private String updatedAt;

    /** 完成时间 */
    private String finishedAt;

    /** 当前阶段是否允许安全取消 */
    private boolean cancellable;

    /** 是否已接收取消请求 */
    private boolean cancelRequested;

    /** 部署配置分项诊断结果 */
    private Map<String, DeploymentCheckVo> checks;
}
