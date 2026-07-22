package com.aid.upgrade.dto;

import java.util.List;

import lombok.Data;

/**
 * 升级器运行日志（页面安装引导与故障排查展示）
 *
 * @author 视觉AID
 */
@Data
public class UpdaterLogVo {

    /** 日志文件路径（升级器健康文件同目录 updater.log） */
    private String logFile;

    /** 日志尾部内容（按行，最多返回最近200行） */
    private List<String> lines;

    /** 日志不可读时的原因说明 */
    private String message;
}
