package com.aid.rps.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建主表资产请求DTO（角色/场景/道具），顶层字段兼容驼峰与 snake_case 写法。
 *
 * @author 视觉AID
 */
@Data
public class RpsCreateRequest
{

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影模式传0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 资产名称 */
    @NotBlank(message = "资产名称不能为空")
    @Size(max = 100, message = "资产名称不能超过100个字符")
    private String name;

    /** 资产类型: scene/character/prop */
    @NotBlank(message = "资产类型不能为空")
    private String assetType;

    /** 别名(逗号分隔)，兼容 aliases / aliasesName */
    @JsonAlias("aliases")
    private String aliasesName;

    /** 角色介绍 / 场景描述 / 道具视觉描述 */
    private String introduction;

    /** 性别(角色) */
    private String gender;

    /** 年龄段(角色)，兼容 age_range / ageRange */
    @JsonAlias("age_range")
    private String ageRange;

    /** 角色重要性层级: S/A/B/C/D，兼容 role_level / roleLevel */
    @JsonAlias("role_level")
    private String roleLevel;

    /** 角色档案JSON，兼容 profile_data / profileData */
    @JsonAlias("profile_data")
    private String profileData;

    /** 角色多形态定义数组（character 专属必填），兼容 expected_appearances / expectedAppearances */
    @JsonAlias("expected_appearances")
    private List<ExpectedAppearanceItem> expectedAppearances;

    /** 简要说明(场景用途/道具简介) */
    private String summary;

    /** 场景可落位位置JSON数组（场景专属），兼容 available_slots / availableSlots */
    @JsonAlias("available_slots")
    private String availableSlots;

    /** 场景是否有人群: 0=无 1=有（场景专属），兼容 has_crowd / hasCrowd */
    @JsonAlias("has_crowd")
    private Integer hasCrowd;

    /** 人群类型描述（场景专属），兼容 crowd_description / crowdDescription */
    @JsonAlias("crowd_description")
    private String crowdDescription;
    /** 角色原型标签（如"智者"/"侠客"） */
    private String archetype;

    /** 所处时代（如"民国"/"未来科幻"），兼容 era_period */
    @JsonAlias("era_period")
    private String eraPeriod;

    /** 职业 */
    private String occupation;

    /** 服装等级（数值），兼容 costume_tier */
    @JsonAlias("costume_tier")
    private Integer costumeTier;

    /** 社会阶层（如"中产"/"贵族"），兼容 social_class */
    @JsonAlias("social_class")
    private String socialClass;

    /** 视觉关键词数组，兼容 visual_keywords */
    @JsonAlias("visual_keywords")
    private List<String> visualKeywords;

    /** 性格标签数组，兼容 personality_tags */
    @JsonAlias("personality_tags")
    private List<String> personalityTags;

    /** 推荐色系数组，兼容 suggested_colors */
    @JsonAlias("suggested_colors")
    private List<String> suggestedColors;

    /** 主要识别特征，兼容 primary_identifier */
    @JsonAlias("primary_identifier")
    private String primaryIdentifier;
}
