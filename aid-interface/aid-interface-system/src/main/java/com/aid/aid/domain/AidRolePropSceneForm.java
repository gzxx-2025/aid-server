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
 * 角色道具场景形态(从)对象 aid_role_prop_scene_form
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_role_prop_scene_form")
public class AidRolePropSceneForm extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联的主表ID (对应aid_role_prop_scene表的id) */
    @Excel(name = "关联的主表ID (对应aid_role_prop_scene表的id)")
    private Long assetId;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 形态名称 (例如：常服、战损版、侧脸) */
    @Excel(name = "形态名称 (例如：常服、战损版、侧脸)")
    private String name;

    /** 形象变更原因(如：初始形象、战斗装束、出浴状态) */
    @Excel(name = "形象变更原因")
    private String changeReason;

    /** AI生成提示词 (该形态的专属特征提示词) */
    @Excel(name = "AI生成提示词 (该形态的专属特征提示词)")
    private String promptText;

    // 图片相关数据全部迁移到 aid_role_prop_scene_form_image；
    // 本表只承担"形态定义"语义（形态名 / changeReason / promptText / visualDescStatus）

    /** 视觉描述状态: pending-待生成, completed-已完成 */
    private String visualDescStatus;

    /** 创建来源: manual-手动创建, auto-自动提取 */
    private String createSource;

    /** 删除标志 (0代表存在 2代表删除) */
    private String delFlag;

}
