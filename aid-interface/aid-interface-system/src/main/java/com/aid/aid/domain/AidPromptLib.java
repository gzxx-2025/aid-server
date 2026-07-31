package com.aid.aid.domain;

import java.io.Serializable;
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
 * 提示词素材库(官方预设与用户自定义)对象 aid_prompt_lib
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_prompt_lib")
public class AidPromptLib extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户ID (0表示官方预设，非0表示用户自定义私有) */
    @Excel(name = "所属用户ID (0表示官方预设，非0表示用户自定义私有)")
    private Long userId;

    /** 提示词分类 (枚举如: style视频风格, camera镜头语言, subject主体描述等) */
    @Excel(name = "提示词分类 (枚举如: style视频风格, camera镜头语言, subject主体描述等)")
    private String promptType;

    /** 提示词名称 (用于前端UI下拉框/卡片展示，如: 赛博朋克风、希区柯克变焦) */
    @Excel(name = "提示词名称 (用于前端UI下拉框/卡片展示，如: 赛博朋克风、希区柯克变焦)")
    private String promptName;

    /** 提示词具体内容 (发给大模型的实际文本/咒语) */
    @Excel(name = "提示词具体内容 (发给大模型的实际文本/咒语)")
    private String promptContent;

    /** 提示词英文 */
    @Excel(name = "提示词英文")
    private String promptContentEn;

    /** 效果预览图URL（相对路径） */
    @Excel(name = "效果预览图URL (可选，用于前端展示该提示词的示例效果)")
    @MediaUrl
    private String coverUrl;

    /** 展示排序 (数值越小越靠前，主要用于官方提示词排序) */
    @Excel(name = "展示排序 (数值越小越靠前，主要用于官方提示词排序)")
    private Long sortOrder;

    /** 版本 */
    @Excel(name = "版本")
    private Integer version;

    /** 状态 (0正常 1停用，用于官方下架某个提示词) */
    @Excel(name = "状态 (0正常 1停用，用于官方下架某个提示词)")
    private String status;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
