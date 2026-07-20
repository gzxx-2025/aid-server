package com.aid.aid.domain;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 分镜镜头组拆分计划对象 aid_storyboard_shot_group_plan。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_storyboard_shot_group_plan")
public class AidStoryboardShotGroupPlan extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 分镜脚本批量任务ID */
    @Excel(name = "任务ID")
    private Long taskId;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 场次剧情ID */
    @Excel(name = "场次剧情ID")
    private Long scenePlotId;

    /** 场景资产ID */
    @Excel(name = "场景资产ID")
    private Long sceneId;

    /** 来源场次编码 */
    @Excel(name = "来源场次编码")
    private String sourceSceneCode;

    /** 场次内镜头组编码，如001 */
    @Excel(name = "镜头组编码")
    private String groupCode;

    /** 场次内镜头组序号 */
    @Excel(name = "镜头组序号")
    private Integer groupIndex;

    /** 展示编码，如4-1 */
    @Excel(name = "展示编码")
    private String displayCode;

    /** 当前镜头组对应原始剧情内容 */
    @Excel(name = "镜头组剧情内容")
    private String plotContent;

    /** 当前镜头组角色JSON */
    @Excel(name = "镜头组角色JSON")
    private String charactersJson;

    /** 当前镜头组关键台词JSON */
    @Excel(name = "镜头组关键台词JSON")
    private String keyDialoguesJson;

    /** 拆分原因 */
    @Excel(name = "拆分原因")
    private String splitReason;

    /** 上一组承接摘要 */
    @Excel(name = "上一组承接摘要")
    private String previousSummary;

    /** 下一组承接摘要 */
    @Excel(name = "下一组承接摘要")
    private String nextSummary;

    /** 状态：PENDING/PROCESSING/SUCCESS/FAILED/CANCELLED */
    @Excel(name = "状态")
    private String status;

    /** 生成成功后的分镜ID */
    @Excel(name = "分镜ID")
    private Long storyboardId;

    /** 失败原因 */
    @Excel(name = "失败原因")
    private String errorMsg;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}
