package com.aid.skill.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 统一 Skill 运行视图；媒体状态和金额只从关联 aid_media_task 派生。 */
@Data
@Builder
public class SkillInvocationVO {
    private Long runId;
    private Long rootRunId;
    private Long parentRunId;
    private String skillCode;
    private Long skillVersionId;
    private String modelCode;
    private Integer generation;
    private String status;
    private String stage;
    private String operation;
    private String qualityMode;
    private String prompt;
    private String responseMode;
    private String assistantMessage;
    private String outputText;
    private String reviewReport;
    /** VALID、NORMALIZED、REPAIRED、RAW_FALLBACK 或 NOT_APPLICABLE。 */
    private String formatStatus;
    private List<ReplacementView> replacements;
    private String errorMessage;
    private InputRequestView requiredInput;
    private List<TaskView> tasks;

    @Data
    @Builder
    public static class HistoryPage {
        private List<SkillInvocationVO> data;
        private Boolean hasMore;
    }

    @Data
    @Builder
    public static class InputRequestView {
        private Long runId;
        private Long requestId;
        private Integer round;
        private String title;
        private String readiness;
        private List<FactItem> confirmedFacts;
        private List<String> assumptions;
        private List<QuestionItem> questions;
        private String contextVersion;
        private String schemaDigest;
    }

    @Data
    @Builder
    public static class FactItem {
        private String field;
        private String value;
    }

    @Data
    @Builder
    public static class QuestionItem {
        private String id;
        private String field;
        private Boolean required;
        private String question;
        private String reason;
        private String inputType;
        private List<OptionItem> options;
        private String recommendedValue;
        private String defaultValue;
        private Boolean allowCustom;
        private Boolean allowAiDecide;
        private Integer min;
        private Integer max;
        private String unit;
    }

    @Data
    @Builder
    public static class OptionItem {
        private String label;
        private String value;
    }

    @Data
    @Builder
    public static class TaskView {
        private Long stepId;
        private String stepKey;
        private String stepExecutionId;
        private Integer workflowAttempt;
        private Long mediaTaskId;
        private String mediaStatus;
        private String billingStatus;
    }

    @Data
    @Builder
    public static class ReplacementView {
        private Integer referenceIndex;
        private String selectionId;
        private String originalText;
        private String replacement;
        private Integer lineNumber;
        private Integer charStart;
        private Integer charEnd;
        private String documentVersion;
    }

    @Data
    @Builder
    public static class EventView {
        private Long seq;
        private String eventType;
        private String stage;
        private Long stepId;
        private Long mediaTaskId;
        private String payloadJson;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
        private java.util.Date createTime;
    }
}
