package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 剧本原文对象 aid_comic_script
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_comic_script")
public class AidComicScript extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 集数ID(电影为0) */
    @Excel(name = "集数ID(电影为0)")
    private Long episodeId;

    /** 所属用户ID */
    @Excel(name = "所属用户ID")
    private Long userId;

    /** 用户上传的原版剧本 */
    @Excel(name = "用户上传的原版剧本")
    private String originalText;

    /** 历史兼容字段，业务不再读写 */
    @JsonIgnore
    @TableField(value = "simplified_text", select = false,
            insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String simplifiedText;

    /** 是否已执行资产提取(0否 1是) */
    @Excel(name = "是否已执行资产提取(0否 1是)")
    private Integer isExtracted;

    /** 剧集版本 */
    @Excel(name = "剧集版本")
    private Integer comicVersion;

    /** 状态(0草稿 1使用 2历史版本) */
    @Excel(name = "状态(0草稿 1使用 2历史版本)")
    private Integer status;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
