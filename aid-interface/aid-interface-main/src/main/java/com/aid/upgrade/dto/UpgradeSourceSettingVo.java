package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 升级源配置展示对象
 *
 * @author 视觉AID
 */
@Data
public class UpgradeSourceSettingVo {

    /** 版本更新清单地址 */
    private String manifestUrl;

    /** 升级器下载地址 */
    private String updaterDownloadUrl;

    /** 升级器健康文件路径 */
    private String updaterHealthFile;

    /** 升级器任务文件路径 */
    private String updaterTaskFile;

    /** 接收版本渠道：stable=仅正式版，all=正式版+测试版 */
    private String releaseChannel;

    /** 升级前自动备份的保留份数 */
    private Integer keepBackups;
}
