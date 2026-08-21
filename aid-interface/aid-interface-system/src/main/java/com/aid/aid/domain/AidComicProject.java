package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 漫剧项目主对象 aid_comic_project
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_comic_project")
public class AidComicProject extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户ID */
    @Excel(name = "所属用户ID")
    private Long userId;

    /** 项目名称 */
    @Excel(name = "项目名称")
    private String projectName;

    /** 项目描述 */
    @Excel(name = "项目描述")
    private String projectDesc;

    /** 待审核项目名称；审核通过后原子替换 project_name */
    private String pendingProjectName;

    /** 待审核项目描述；审核通过后原子替换 project_desc */
    private String pendingProjectDesc;

    /** 类型: series剧集, movie电影 */
    @Excel(name = "类型: series剧集, movie电影")
    private String projectType;

    /** 封面图（相对路径） */
    @Excel(name = "封面图")
    @MediaUrl
    private String coverUrl;

    /** 待审核封面图；审核通过后原子替换 cover_url */
    @MediaUrl
    private String pendingCoverUrl;

    /** 画面比例(16:9, 9:16等) */
    @Excel(name = "画面比例(16:9, 9:16等)")
    private String aspectRatio;

    /** 剧本类型(剧情演绎, 真人解说) */
    @Excel(name = "剧本类型(剧情演绎, 真人解说)")
    private String scriptType;

    /** 视频风格名称（前端传什么存什么，不做枚举校验） */
    @Excel(name = "视频风格名称")
    private String videoStyleType;

    /** 视频风格值字符串（前端传什么存什么） */
    @Excel(name = "视频风格值")
    private String videoStyleValue;

    /** 项目风格来源：official 官方 / custom 用户自定义 */
    private String styleSource;

    /** 项目风格在对应来源表中的资产ID */
    private Long styleAssetId;

    /** 项目隐藏风格提示词快照 JSON（character / scene / prop） */
    @ToString.Exclude
    private String hiddenStylePromptJson;

    /** 默认生成模式(economy经济, performance性能) */
    @Excel(name = "默认生成模式(economy经济, performance性能)")
    private String defaultGenMode;

    /** 默认创作模式(i2v图生视频, multi多参生视频) */
    @Excel(name = "默认创作模式(i2v图生视频, multi多参生视频)")
    private String defaultCreationMode;

    /**
     * 当前步骤 (电影: 1~7, 剧集固定-1)。
     */
    @Excel(name = "当前步骤")
    private Integer currentStep;

    /** 状态(0草稿 1制作中  2完成未提交 3审核中 4审核通过 5审核失败) */
    @Excel(name = "状态(0草稿 1制作中  2完成未提交 3审核中 4审核通过 5审核失败)")
    private Integer status;

    /** 状态原因 */
    @Excel(name = "状态原因")
    private String statusReason;

    /** 项目是否公开 */
    @Excel(name = "项目是否公开")
    private String isPublic;

    /** 最近一次公开发布时间（关闭公开不清空，重新发布覆盖） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "publish_time")
    private Date publishTime;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
