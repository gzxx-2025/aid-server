package com.aid.upgrade.dto;

import java.util.List;

import lombok.Data;

/**
 * 系统版本与升级状态
 *
 * @author 视觉AID
 */
@Data
public class UpgradeStatusVo {

    /** 当前运行版本 */
    private String currentVersion;

    /** 远端最新版本 */
    private String latestVersion;

    /** 是否有新版本 */
    private boolean hasUpdate;

    /** 更新日志 */
    private String releaseNotes;

    /** Gitee 发布页地址 */
    private String giteeReleaseUrl;

    /** GitHub 发布页地址 */
    private String githubReleaseUrl;

    /** 远端版本发布时间 */
    private String publishedAt;

    /** 最近一次检查时间（yyyy-MM-dd HH:mm:ss） */
    private String checkedAt;

    /** 检查失败原因（成功时为空） */
    private String checkError;

    /** 版本更新清单地址（页面只读展示） */
    private String manifestUrl;

    /** 升级器下载地址（页面只读展示） */
    private String updaterDownloadUrl;

    /** 升级器状态 */
    private UpdaterStatusVo updater;

    /** 官方API地址同步状态 */
    private OfficialApiStatusVo officialApi;

    /** 当前版本可选择的回退版本 */
    private List<UpgradeManifest.RollbackRelease> rollbackReleases;
}
