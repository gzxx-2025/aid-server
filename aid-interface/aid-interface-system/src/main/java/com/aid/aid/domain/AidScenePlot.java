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
 * 剧情节拍对象 aid_scene_plot。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_scene_plot")
public class AidScenePlot extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属场景 aid_role_prop_scene.id（外键） */
    @Excel(name = "场景ID")
    @TableField(value = "scene_id")
    private Long sceneId;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 全局递增场次序号（来自场景提取 LLM 输出，被 SceneCodeAllocator 全局覆盖后写入） */
    @Excel(name = "场次序号")
    private String sceneCode;

    /** 剧情原文（LLM 逐字保留） */
    @Excel(name = "剧情原文")
    private String plotContent;

    /** 剧情梗概 */
    @Excel(name = "剧情梗概")
    private String plotSummary;

    /** 出场人物 JSON 数组 */
    @Excel(name = "出场人物")
    private String characters;

    /** 人物动作事件 */
    @Excel(name = "人物动作事件")
    private String characterActions;

    /** 角色状态 */
    @Excel(name = "角色状态")
    private String characterStates;

    /** 关键台词 JSON 数组 */
    @Excel(name = "关键台词")
    private String keyDialogues;

    /** 场次功能 叙事功能;故事节奏 */
    @Excel(name = "场次功能")
    private String sceneFunction;

    /** 时间坐标 凌晨/早晨/上午/中午/下午/黄昏/夜晚/深夜/子夜 */
    @Excel(name = "时间坐标")
    private String timeOfDay;

    /** 年代坐标 时代-年代-地域 */
    @Excel(name = "年代坐标")
    private String eraCoordinate;

    /** 日期坐标 季节-日期属性 */
    @Excel(name = "日期坐标")
    private String dateCoordinate;

    /** 气候天象 气候-天气-天象 */
    @Excel(name = "气候天象")
    private String weather;

    /** 创建来源 auto/manual */
    @Excel(name = "创建来源")
    private String createSource;

    /** 删除标志 0存在 2软删 */
    private String delFlag;
}
