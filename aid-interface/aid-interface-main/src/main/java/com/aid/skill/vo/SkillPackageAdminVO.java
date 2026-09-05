package com.aid.skill.vo;

import com.aid.skill.dto.SkillPackageAdminRequests;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Skill 版本包管理响应。 */
public final class SkillPackageAdminVO {
    private SkillPackageAdminVO() { }

    @Data
    public static class VersionSummary {
        private Long id;
        private Long skillId;
        private String versionCode;
        private String publishStatus;
        private String packageDigest;
        private String status;
        private Boolean current;
        private String createBy;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class VersionPageResult extends SkillAdminVO.PageResult<VersionSummary> {
        private Long currentVersionId;

        public VersionPageResult() { }

        public VersionPageResult(long total, List<VersionSummary> data, Long currentVersionId) {
            super(total, data);
            this.currentVersionId = currentVersionId;
        }
    }

    @Data
    public static class ResourceItem {
        private Long id;
        private String resourceKey;
        private String resourceType;
        private String objectKey;
        private String contentDigest;
        private String mimeType;
        private Long sizeBytes;
        private String routeJson;
        private String content;
    }

    @Data
    public static class RelationItem {
        private Long id;
        private String relationKey;
        private Long childSkillId;
        private String childSkillCode;
        private Long childVersionId;
        private String childVersionCode;
        private Boolean requiredFlag;
    }

    @Data
    public static class VersionDetail {
        private Long id;
        private Long skillId;
        private String skillCode;
        private String versionCode;
        private String visibility;
        private String invocationScope;
        private String publishStatus;
        private String executorType;
        private String modelCode;
        private String modelConfigJson;
        private String defaultModelCode;
        private List<String> selectableModelCodes = new ArrayList<>();
        private String packageDigest;
        private String manifestJson;
        private String inputSchemaJson;
        private String outputSchemaJson;
        private String systemPrompt;
        private String definitionJson;
        private Integer maxOutputTokens;
        private Integer contextWindowTokens;
        private Integer safetyMarginTokens;
        private String status;
        private Boolean current;
        private String createBy;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date createTime;
        private List<ResourceItem> resources = new ArrayList<>();
        private List<RelationItem> relations = new ArrayList<>();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DraftDetail extends SkillPackageAdminRequests.PackagePayload {
        private Long draftId;
        private String baseVersionCode;
        private String skillCode;
        private String executorType;
        private String invocationScope;
        private String draftDigest;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    @Schema(description = "包校验问题")
    public static class ValidationIssue {
        private String field;
        private String message;

        public ValidationIssue() { }

        public ValidationIssue(String field, String message) {
            this.field = field;
            this.message = message;
        }
    }

    @Data
    public static class ValidationResult {
        private Boolean valid;
        private String draftDigest;
        private List<ValidationIssue> errors = new ArrayList<>();
        private List<ValidationIssue> warnings = new ArrayList<>();
    }

    @Data
    public static class DependencyVersionOption {
        private Long id;
        private String versionCode;
        private Boolean current;
    }

    @Data
    public static class DependencySkillOption {
        private Long skillId;
        private String skillCode;
        private String name;
        private Long currentVersionId;
    }

    @Data
    public static class DependencyLabel {
        private Long childSkillId;
        private String childSkillCode;
        private String childSkillName;
        private Long childVersionId;
        private String childVersionCode;
        private Boolean current;
    }
}
