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

    /** 允许一键直升的最低版本；当前版本低于它时需先升级到中间版本 */
    private String minimumVersion;

    /** 当前版本是否低于最低直升版本（true 时页面应引导先升中间版本） */
    private boolean belowMinimumVersion;

    /** 更新日志 */
    private String releaseNotes;

    /** Gitee 发布页地址 */
    private String giteeReleaseUrl;

    /** GitHub 发布页地址 */
    private String githubReleaseUrl;

    /** 最新版本所属渠道：stable=正式版，beta=测试版 */
    private String latestChannel;

    /** 使用教程/文档地址（清单动态下发） */
    private String docsUrl;

    /** 提示词开发指南地址（清单动态下发） */
    private String promptDocsUrl;

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
