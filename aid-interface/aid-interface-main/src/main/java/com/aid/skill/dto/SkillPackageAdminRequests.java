package com.aid.skill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Skill 不可变版本包管理请求。 */
public final class SkillPackageAdminRequests {
    private static final String SEMVER_PATTERN =
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
                    + "(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?";

    private SkillPackageAdminRequests() { }

    @Data
    @Schema(description = "Skill ID 请求")
    public static class SkillRequest {
        @NotNull(message = "Skill ID必填")
        @Positive(message = "Skill ID错误")
        private Long skillId;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "Skill 版本分页请求")
    public static class VersionPageRequest extends SkillRequest {
        @NotNull(message = "页码必填")
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;

        @NotNull(message = "页大小必填")
        @Min(value = 1, message = "页大小错误")
        @Max(value = 100, message = "页大小过大")
        private Integer pageSize = 20;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "可选子 Skill 分页请求")
    public static class DependencySkillPageRequest extends SkillRequest {
        @Size(max = 100, message = "搜索词过长")
        private String keyword;

        @NotNull(message = "页码必填")
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;

        @NotNull(message = "页大小必填")
        @Min(value = 1, message = "页大小错误")
        @Max(value = 50, message = "页大小过大")
        private Integer pageSize = 20;
    }

    @Data
    @Schema(description = "可选子 Skill 版本分页请求")
    public static class DependencyVersionPageRequest {
        @NotNull(message = "父Skill ID必填")
        @Positive(message = "父Skill ID错误")
        private Long parentSkillId;

        @NotNull(message = "子Skill ID必填")
        @Positive(message = "子Skill ID错误")
        private Long childSkillId;

        @Size(max = 100, message = "搜索词过长")
        private String keyword;

        @NotNull(message = "页码必填")
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;

        @NotNull(message = "页大小必填")
        @Min(value = 1, message = "页大小错误")
        @Max(value = 100, message = "页大小过大")
        private Integer pageSize = 20;
    }

    @Data
    @Schema(description = "已选子 Skill 版本标签批量请求")
    public static class DependencyLabelRequest {
        @NotNull(message = "父Skill ID必填")
        @Positive(message = "父Skill ID错误")
        private Long parentSkillId;

        @NotNull(message = "子版本必填")
        @Size(min = 1, max = 16, message = "子版本数量错误")
        private List<@NotNull(message = "子版本不能为空") @Positive(message = "子版本错误") Long> versionIds;
    }

    @Data
    @Schema(description = "版本 ID 请求")
    public static class VersionRequest {
        @NotNull(message = "版本ID必填")
        @Positive(message = "版本ID错误")
        private Long id;
    }

    @Data
    @Schema(description = "Skill 草稿读取请求")
    public static class DraftRequest {
        @NotNull(message = "Skill ID必填")
        @Positive(message = "Skill ID错误")
        private Long skillId;

        @Schema(description = "没有编辑中草稿时，用该版本生成只读草稿种子")
        @Positive(message = "基础版本错误")
        private Long baseVersionId;
    }

    @Data
    @Schema(description = "Skill 资源编辑项")
    public static class ResourceItem {
        @NotBlank(message = "资源标识必填")
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,99}", message = "资源标识错误")
        private String resourceKey;

        @Size(max = 30, message = "资源类型过长")
        private String resourceType = "REFERENCE";

        @Size(max = 100, message = "媒体类型过长")
        private String mimeType = "text/markdown";

        @NotBlank(message = "资源内容必填")
        @Size(max = 102400, message = "资源内容过长")
        private String content;

        @Size(max = 8000, message = "路由规则过长")
        private String routeJson = "{}";
    }

    @Data
    @Schema(description = "固定子 Skill 版本关系")
    public static class RelationItem {
        @NotBlank(message = "关系标识必填")
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}", message = "关系标识错误")
        private String relationKey;

        @NotNull(message = "子Skill必填")
        @Positive(message = "子Skill错误")
        private Long childSkillId;

        @NotNull(message = "子版本必填")
        @Positive(message = "子版本错误")
        private Long childVersionId;

        private Boolean requiredFlag = true;
    }

    @Data
    @Schema(description = "Skill 可执行包内容")
    public static class PackagePayload {
        @Schema(description = "开始编辑时的基础版本 ID；尚无当前版本时可为空")
        @Positive(message = "基础版本错误")
        private Long baseVersionId;

        @NotNull(message = "Skill ID必填")
        @Positive(message = "Skill ID错误")
        private Long skillId;

        /** 旧客户端兼容字段；服务端以 defaultModelCode 为准并同步镜像。 */
        @Size(max = 100, message = "模型编码过长")
        private String modelCode;

        @Size(max = 100, message = "默认模型编码过长")
        private String defaultModelCode;

        @Size(max = 20, message = "可选模型数量不能超过20个")
        private List<@NotBlank(message = "可选模型编码不能为空")
                @Size(max = 100, message = "可选模型编码过长") String> selectableModelCodes = new ArrayList<>();

        @NotBlank(message = "提示词必填")
        @Size(max = 100000, message = "提示词过长")
        private String systemPrompt;

        @NotBlank(message = "输入结构必填")
        @Size(max = 100000, message = "输入结构过长")
        private String inputSchemaJson;

        @NotBlank(message = "输出结构必填")
        @Size(max = 100000, message = "输出结构过长")
        private String outputSchemaJson;

        @Size(max = 100000, message = "定义内容过长")
        private String definitionJson;

        @NotNull(message = "输出上限必填")
        @Min(value = 1, message = "输出上限错误")
        @Max(value = 1600000, message = "输出上限过大")
        private Integer maxOutputTokens;

        @NotNull(message = "上下文窗口必填")
        @Min(value = 1024, message = "上下文窗口错误")
        @Max(value = 1600000, message = "上下文窗口过大")
        private Integer contextWindowTokens;

        @NotNull(message = "安全余量必填")
        @Min(value = 0, message = "安全余量错误")
        @Max(value = 1600000, message = "安全余量过大")
        private Integer safetyMarginTokens;

        @Valid
        @Size(max = 64, message = "资源数量过多")
        private List<@NotNull(message = "资源项不能为空") ResourceItem> resources = new ArrayList<>();

        @Valid
        @Size(max = 16, message = "子Skill过多")
        private List<@NotNull(message = "关系项不能为空") RelationItem> relations = new ArrayList<>();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "保存 Skill 草稿")
    public static class DraftSaveRequest extends PackagePayload {
        @Positive(message = "草稿ID错误")
        private Long draftId;

        @Size(max = 64, message = "草稿摘要错误")
        @Pattern(regexp = "[0-9a-f]{64}", message = "草稿摘要错误")
        private String draftDigest;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "校验 Skill 草稿")
    public static class DraftValidateRequest extends PackagePayload { }

    @Data
    @Schema(description = "发布 Skill 草稿")
    public static class DraftPublishRequest {
        @NotNull(message = "草稿ID必填")
        @Positive(message = "草稿ID错误")
        private Long draftId;

        @NotBlank(message = "草稿摘要必填")
        @Pattern(regexp = "[0-9a-f]{64}", message = "草稿摘要错误")
        private String draftDigest;

        @NotBlank(message = "版本号必填")
        @Pattern(regexp = SEMVER_PATTERN, message = "版本号错误")
        @Size(max = 64, message = "版本号过长")
        private String versionCode;
    }

    @Data
    @Schema(description = "放弃 Skill 草稿")
    public static class DraftDiscardRequest {
        @NotNull(message = "草稿ID必填")
        @Positive(message = "草稿ID错误")
        private Long draftId;

        @NotBlank(message = "草稿摘要必填")
        @Pattern(regexp = "[0-9a-f]{64}", message = "草稿摘要错误")
        private String draftDigest;
    }

    @Data
    @Schema(description = "切换当前 Skill 版本")
    public static class VersionActivateRequest {
        @NotNull(message = "Skill ID必填")
        @Positive(message = "Skill ID错误")
        private Long skillId;

        @NotNull(message = "版本ID必填")
        @Positive(message = "版本ID错误")
        private Long versionId;

        @Schema(description = "客户端观察到的当前版本 ID；首次激活时可为空")
        @Positive(message = "当前版本错误")
        private Long expectedCurrentVersionId;
    }
}
