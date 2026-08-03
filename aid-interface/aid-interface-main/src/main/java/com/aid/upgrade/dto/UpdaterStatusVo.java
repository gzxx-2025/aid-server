package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 升级器运行状态
 *
 * @author 视觉AID
 */
@Data
public class UpdaterStatusVo {

    /** 升级器状态：NOT_INSTALLED/STOPPED/AVAILABLE/INCOMPATIBLE/UNKNOWN */
    private String status;

    /** 本地升级器版本 */
    private String version;

    /** 升级器任务协议版本 */
    private Integer protocolVersion;

    /** 部署方式（升级器按自身配置上报）：systemd=手动部署 / docker=容器部署 */
    private String serviceManager;

    /** 发布方最新升级器版本（来自更新清单） */
    private String latestVersion;

    /** 升级器是否有新版本（支持在线升级） */
    private boolean hasUpdate;

    /** 状态说明（给页面展示） */
    private String message;

    /** 是否可执行一键升级 */
    private boolean ready;

    /** 最近一次任务执行结果（升级器透出） */
    private UpdaterLastTaskVo lastTask;

    /** 升级器读取到的脱敏部署运行配置 */
    private DeploymentConfigVo deploymentConfig;
}
