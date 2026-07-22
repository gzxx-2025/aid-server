package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 升级源配置保存参数
 *
 * @author 视觉AID
 */
@Data
public class UpgradeSourceSaveDto {

    /** 版本更新清单地址（自动维护项，null 表示不修改） */
    private String manifestUrl;

    /** 升级器下载地址（自动维护项，null 表示不修改） */
    private String updaterDownloadUrl;

    /** 升级器健康文件路径（自动维护项，null 表示不修改） */
    private String updaterHealthFile;

    /** 升级器任务文件路径（自动维护项，null 表示不修改） */
    private String updaterTaskFile;

    /** 接收版本渠道：stable=仅正式版，all=正式版+测试版（null 表示不修改） */
    private String releaseChannel;

    /** 升级前自动备份的保留份数（1-50，留空按默认值 3） */
    private Integer keepBackups;
}
