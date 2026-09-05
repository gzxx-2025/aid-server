package com.aid.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Web/Open API/CLI 共用的 Skill 调用 DTO；调用来源和媒体幂等键由服务端确定。 */
public final class SkillInvocationRequests {
    private SkillInvocationRequests() { }

    @Data
    @Schema(description = "统一 Skill 调用")
    public static class InvokeRequest {
        @NotBlank(message = "Skill编码必填")
        @Size(max = 64, message = "Skill编码过长")
        private String skillCode;

        @Size(max = 100, message = "模型编码过长")
        private String modelCode;

        @NotBlank(message = "请求标识必填")
        @Size(max = 64, message = "请求标识过长")
        @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "请求标识格式错误")
        private String idempotencyKey;

        private Boolean force;

        @NotNull(message = "项目ID必填")
        @Min(value = 1, message = "项目ID错误")
        private Long projectId;

        @Min(value = 0, message = "剧集ID错误")
        private Long episodeId;

        @Min(value = 1, message = "上一轮 Run ID 错误")
        private Long parentRunId;

        @Size(max = 100000, message = "创作要求过长")
        private String prompt;

        @Size(max = 30, message = "动作模式过长")
        private String operation = "AUTO";

        @Size(max = 20, message = "质量模式过长")
        private String qualityMode = "AUTO";

        @Size(max = 2000, message = "风格过长")
        private String style;

        @Size(max = 100, message = "类型过长")
        private String genre;

        @Size(max = 30, message = "语言过长")
        private String language;

        @Min(value = 1, message = "时长过短")
        @Max(value = 21600, message = "时长过长")
        private Integer targetDurationSeconds;

        @Valid
        @Size(max = 20, message = "参考内容过多")
        private List<ReferenceItem> references;
    }

    @Data
    public static class ReferenceItem {
        @NotBlank(message = "参考类型必填")
        @Pattern(regexp = "TEXT|PROJECT_ASSET", message = "参考类型错误")
        private String referenceType;
        @Min(value = 1, message = "资源ID错误")
        private Long resourceId;
        @Size(max = 20000, message = "参考文本过长")
        private String text;
        @Size(max = 2000, message = "参考前文过长")
        private String contextBefore;
        @Size(max = 2000, message = "参考后文过长")
        private String contextAfter;
        @Size(max = 100, message = "选段标识过长")
        private String selectionId;
        @Min(value = 1, message = "选段行号错误")
        private Integer lineNumber;
        @Min(value = 0, message = "选段起始位置错误")
        private Integer charStart;
        @Min(value = 1, message = "选段结束位置错误")
        private Integer charEnd;
        @Size(max = 100, message = "文档版本过长")
        private String documentVersion;
    }

    @Data
    @Schema(description = "回答 Skill 动态问题")
    public static class RespondRequest {
        @NotNull(message = "Run ID必填")
        private Long runId;
        @NotNull(message = "输入请求ID必填")
        private Long requestId;
        @NotBlank(message = "响应标识必填")
        @Size(max = 64, message = "响应标识过长")
        @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "响应标识格式错误")
        private String responseKey;
        @NotBlank(message = "上下文版本必填")
        @Size(max = 64, message = "上下文版本过长")
        private String contextVersion;
        @NotBlank(message = "问题结构摘要必填")
        @Size(max = 64, message = "问题结构摘要过长")
        private String schemaDigest;
        @Valid
        @Size(max = 4, message = "回答数量过多")
        private List<AnswerItem> answers;
        @Size(max = 20000, message = "自然语言回答过长")
        private String naturalLanguageAnswer;
    }

    @Data
    public static class AnswerItem {
        @NotBlank(message = "问题ID必填")
        @Size(max = 64, message = "问题ID过长")
        private String questionId;
        @NotBlank(message = "字段必填")
        @Size(max = 64, message = "字段过长")
        private String field;
        @NotNull(message = "答案必填")
        private Object value;
    }

    @Data
    public static class RunRequest {
        @NotNull(message = "Run ID必填")
        private Long runId;
    }

    @Data
    public static class HistoryRequest {
        @NotNull(message = "项目ID必填")
        @Min(value = 1, message = "项目ID错误")
        private Long projectId;

        @Min(value = 0, message = "剧集ID错误")
        private Long episodeId;

        @NotBlank(message = "Skill编码必填")
        @Size(max = 64, message = "Skill编码过长")
        private String skillCode;

        @Min(value = 1, message = "历史游标错误")
        private Long beforeRunId;

        @Min(value = 1, message = "页大小错误")
        @Max(value = 50, message = "页大小过大")
        private Integer pageSize = 30;
    }

    @Data
    public static class EventPageRequest {
        @NotNull(message = "Run ID必填")
        private Long runId;
        @Min(value = 0, message = "事件序号错误")
        private Long afterSeq = 0L;
        @Min(value = 1, message = "页大小错误")
        @Max(value = 200, message = "页大小过大")
        private Integer pageSize = 100;
    }
}
