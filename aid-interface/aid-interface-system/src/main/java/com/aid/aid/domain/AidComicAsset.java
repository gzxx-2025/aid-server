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
 * 项目提取资产对象 aid_comic_asset
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_comic_asset")
public class AidComicAsset extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 类型: scene场景, character角色, prop道具 */
    @Excel(name = "类型: scene场景, character角色, prop道具")
    private String assetType;

    /** 资产名称 */
    @Excel(name = "资产名称")
    private String assetName;

    /** 性格/特征描述(用于生成约束) */
    @Excel(name = "性格/特征描述(用于生成约束)")
    private String personalityDesc;

    /** 提示词 */
    @Excel(name = "提示词")
    private String promptText;

    /** 主图（相对路径） */
    @Excel(name = "主图")
    @MediaUrl
    private String imageUrl;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
