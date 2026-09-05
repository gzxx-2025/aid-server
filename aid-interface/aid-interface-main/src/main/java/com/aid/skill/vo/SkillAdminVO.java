package com.aid.skill.vo;

import com.aid.billing.vo.ModelBillingDetailVO;
import com.aid.model.vo.CapabilityVO;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Skill identity, package administration, and versioned Runtime audit views. */
public final class SkillAdminVO {
    private SkillAdminVO() { }

    @Data
    public static class SkillSummary {
        private Long id;
        private String skillCode;
        private String name;
        private String description;
        private String capabilityDescription;
        private String iconUrl;
        private String ownerType;
        private String visibility;
        private String invocationScope;
        private Long currentVersionId;
        private String status;
        private String delFlag;
        private String modelCode;
        private String reasoningPolicy;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date updateTime;
    }

    @Data
    public static class TextModelOption {
        private String modelCode;
        private String modelName;
        private String capabilityJson;
        private CapabilityVO capability;
        @com.aid.common.aid.oss.annotation.MediaUrl
        private String modelLogo;
        private String providerName;
        @com.aid.common.aid.oss.annotation.MediaUrl
        private String providerLogo;
        private ModelBillingDetailVO billing;
        private String status;
        private String delFlag;
        private Boolean available;
        private String unavailableReason;
    }

    @Data
    public static class RunSummary {
        private Long id;
        private Long userId;
        private Long skillId;
        private Long skillVersionId;
        private Long projectId;
        private Long episodeId;
        private String skillConfigHash;
        private String modelCode;
        private String invokeSource;
        private String clientRequestId;
        private Integer generation;
        private String status;
        private String stage;
        private String actionMode;
        private String qualityMode;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date startedAt;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private Date finishedAt;
        private Long durationMillis;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RunItem extends RunSummary {
        private String clientRequestDigest;
        private String executionSnapshotDigest;
        private String resolvedConfigDigest;
        private Long rootRunId;
        private Long parentRunId;
        private String inputJson;
        private String outputJson;
        private String errorMessage;
        private List<RunTaskItem> tasks = new ArrayList<>();
    }

    @Data
    public static class RunTaskItem {
        private Long stepId;
        private Integer stepSeq;
        private String stepKey;
        private String stepExecutionId;
        private Long skillId;
        private Long skillVersionId;
        private String actionMode;
        private Integer workflowAttempt;
        private String orchestrationStatus;
        private Long mediaTaskId;
        private String mediaStatus;
        private String billingStatus;
        /** Decimal string avoids precision loss in JavaScript audit views. */
        private String actualCost;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Paged response")
    public static class PageResult<T> {
        private long total;
        private List<T> data;
    }
}
