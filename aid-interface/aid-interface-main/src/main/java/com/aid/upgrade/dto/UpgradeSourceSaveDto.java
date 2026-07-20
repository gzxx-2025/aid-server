package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 升级源配置保存参数
 *
 * @author 视觉AID
 */
@Data
public class UpgradeSourceSaveDto {

    /** 版本更新清单地址（留空表示暂不配置更新源） */
    private String manifestUrl;

    /** 升级器下载地址 */
    private String updaterDownloadUrl;

    /** 升级器健康文件路径（为空视为未安装升级器） */
    private String updaterHealthFile;

    /** 升级器任务文件路径 */
    private String updaterTaskFile;
}
