package com.aid.upgrade.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 发布方版本更新清单（latest.json）
 *
 * @author 视觉AID
 */
@Data
public class UpgradeManifest {

    /** 清单结构版本 */
    private Integer schemaVersion;

    /** 产品标识 */
    private String product;

    /** 最新产品版本 */
    private String productVersion;

    /** 发布渠道（stable/demo等） */
    private String channel;

    /** 发布时间 */
    private String publishedAt;

    /** 允许升级的最低版本 */
    private String minimumVersion;

    /** 更新日志 */
    private String releaseNotes;

    /** 最新版本统一升级包直链（升级器下载执行） */
    private String packageUrl;

    /** 最新版本统一升级包SHA-256 */
    private String packageSha256;

    /** 发布页地址集合 */
    private ReleasePages releasePages;

    /** 官方API信息 */
    private OfficialApi officialApi;

    /** 升级器信息 */
    private Updater updater;

    /** 可回退版本，发布方按支持范围维护 */
    private List<RollbackRelease> rollbackReleases;

    /**
     * 发布页地址集合
     */
    @Data
    public static class ReleasePages {

        /** Gitee 发布页 */
        private String gitee;

        /** GitHub 发布页 */
        private String github;
    }

    /**
     * 官方API信息
     */
    @Data
    public static class OfficialApi {

        /** 官方统一网关基础地址 */
        private String baseUrl;

        /** 官方API官网地址（注册开通、获取Key入口） */
        private String websiteUrl;
    }

    /**
     * 升级器信息
     */
    @Data
    public static class Updater {

        /** 升级器最新版本 */
        private String version;

        /** 升级器下载页地址（人工下载入口） */
        private String downloadUrl;

        /** 升级器制品直链集合，key为平台标识（如 linux_amd64/linux_arm64） */
        private Map<String, UpdaterPackage> packages;
    }

    /**
     * 升级器平台制品
     */
    @Data
    public static class UpdaterPackage {

        /** 制品直链 */
        private String url;

        /** 制品SHA-256 */
        private String sha256;
    }

    /**
     * 可回退版本及制品兼容信息
     */
    @Data
    public static class RollbackRelease {

        /** 目标版本 */
        private String version;

        /** 发布时间 */
        private String publishedAt;

        /** 回退包下载地址 */
        private String packageUrl;

        /** 回退包SHA-256 */
        private String sha256;

        /** 数据库回退脚本标识或地址 */
        private String databaseRollback;

        /** 是否支持当前数据库结构直接回退 */
        private Boolean databaseCompatible;

        /** 回退说明 */
        private String notes;
    }
}
