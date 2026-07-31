package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 角色道具场景对象 aid_role_prop_scene
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_role_prop_scene")
public class AidRolePropScene extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 名称(场景/角色/道具) */
    @Excel(name = "名称(场景/角色/道具)")
    private String name;

    /** 别名(逗号分隔) */
    @Excel(name = "别名")
    private String aliasesName;

    /** 角色介绍：身份定位、叙述视角映射、与其他角色的关系、常用称呼 */
    @Excel(name = "角色介绍")
    private String introduction;

    /** 性别(角色) */
    @Excel(name = "性别")
    private String gender;

    /** 年龄段(角色) */
    @Excel(name = "年龄段")
    private String ageRange;

    /** 角色重要性层级: S/A/B/C/D */
    @Excel(name = "角色重要性层级")
    private String roleLevel;

    /** 角色档案JSON */
    @Excel(name = "角色档案JSON")
    private String profileData;

    /** 子形象列表JSON */
    @Excel(name = "子形象列表JSON")
    private String expectedAppearances;

    /** 简要说明(场景用途/道具简介) */
    @Excel(name = "简要说明")
    private String summary;

    /** 场景可落位位置JSON数组 */
    @Excel(name = "场景可落位位置")
    private String availableSlots;

    /** 场景是否有人群(0:无 1:有) */
    @Excel(name = "是否有人群")
    private Integer hasCrowd;

    /** 人群类型描述 */
    @Excel(name = "人群类型描述")
    private String crowdDescription;

    /** 类型: scene场景, character角色, prop道具 */
    @Excel(name = "类型: scene场景, character角色, prop道具")
    private String assetType;

    /** 创建来源: manual-手动创建, auto-自动提取 */
    @Excel(name = "创建来源")
    private String createSource;

    /**
     * 该主表场次序号。
     * 来源于场景提取时 LLM 输出的 sceneCode（被 SceneCodeAllocator 全局覆盖后）。
     * 同剧集多个场景按 first_scene_code 升序排列，对应剧情时间线。
     */
    @Excel(name = "首个场次序号")
    private String firstSceneCode;

    /** 删除标志(0代表存在 2代表删除) */
    private String delFlag;

}
