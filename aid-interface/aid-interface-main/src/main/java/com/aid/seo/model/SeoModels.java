package com.aid.seo.model;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** SEO 管理接口的紧凑数据契约。 */
public final class SeoModels {
    private SeoModels() {
    }

    @Data
    public static class Settings {
        private String siteUrl;
        private String siteName;
        private String titleSuffix;
        private String defaultDescription;
        private String defaultKeywords;
        private Boolean baiduEnabled;
        private String baiduSite;
        private Boolean baiduTokenConfigured;
        private Integer submitBatchSize;
        private String robotsDisallow;
        private String robotsPreview;
        private String sitemapUrl;
        private String robotsUrl;
    }

    @Data
    public static class SettingsSave {
        private String siteUrl;
        private String siteName;
        private String titleSuffix;
        private String defaultDescription;
        private String defaultKeywords;
        private Boolean baiduEnabled;
        private String baiduSite;
        private String baiduToken;
        private Boolean clearBaiduToken;
        private Integer submitBatchSize;
        private String robotsDisallow;
    }

    @Data
    public static class PageQuery {
        private Integer pageNum = 1;
        private Integer pageSize = 20;
        private String keyword;
        private String sourceType;
        private String submitStatus;
        private Boolean indexable;
        private Boolean onlyUnsubmitted;
    }

    @Data
    public static class PageSave {
        private Long id;
        private String sourceType;
        private String sourceId;
        private String pagePath;
        private String pageTitle;
        private String metaDescription;
        private String metaKeywords;
        @MediaUrl
        private String ogImageUrl;
        private Boolean indexable;
        private Boolean sitemapEnabled;
        private String status;
        private Date sourceUpdateTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ContentCandidate extends PageSave {
    }

    @Data
    public static class SubmissionRequest {
        private List<Long> pageIds = new ArrayList<>();
    }

    @Data
    public static class PageView {
        private Long id;
        private String sourceType;
        private String sourceId;
        private String pagePath;
        private String canonicalUrl;
        private String pageTitle;
        private String metaDescription;
        private String metaKeywords;
        private String ogImageUrl;
        private Boolean indexable;
        private Boolean sitemapEnabled;
        private String status;
        private String apiStatus;
        private String manualStatus;
        private Integer attemptCount;
        private Date lastAttemptTime;
        private Date acceptedTime;
        private Date nextRetryTime;
        private String lastErrorMessage;
        private Date updateTime;
    }

    @Data
    public static class PageResult {
        private long total;
        private List<PageView> items = new ArrayList<>();
    }

    @Data
    public static class Overview {
        private long totalPages;
        private long indexablePages;
        private long pendingPages;
        private long acceptedPages;
        private long retryPages;
        private Date lastScanTime;
        private Date lastSubmitTime;
        private Integer providerRemain;
        private Boolean baiduReady;
    }

    @Data
    public static class DispatchResult {
        private String batchNo;
        private int selected;
        private int accepted;
        private int rejected;
        private int deferred;
        private Integer remain;
        private String message;
    }

    @Data
    public static class MetaView {
        private String title;
        private String description;
        private String keywords;
        private String canonicalUrl;
        @MediaUrl
        private String imageUrl;
        private String robots;
    }

    /** 不包含原始 HTML、内部创作信息或用户身份数据的公开页面投影。 */
    @Data
    public static class PublicDocument {
        private String title;
        private String description;
        private String keywords;
        private String imageUrl;
        private String videoUrl;
        private String text;
        private String interactivePath;
    }

    @Data
    public static class LogView {
        private Long id;
        private String batchNo;
        private Long pageId;
        private String provider;
        private String channel;
        private String triggerType;
        private String submitStatus;
        private String urlSnapshot;
        private Integer httpStatus;
        private String responseSummary;
        private String errorCode;
        private String errorMessage;
        private String operatorName;
        private Date createTime;
    }
}
