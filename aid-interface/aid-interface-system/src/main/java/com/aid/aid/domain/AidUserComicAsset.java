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
 * 用户自定义漫画参考资产对象 aid_user_comic_asset
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_user_comic_asset")
public class AidUserComicAsset extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 资产类型: reference_character人物参考图, reference_scene场景参考图, reference_prop道具参考图, style风格, pose姿势, expression表情, effect特效, file文件, mood情绪, camera摄影参数 */
    @Excel(name = "资产类型: reference_character人物参考图, reference_scene场景参考图, reference_prop道具参考图, style风格, pose姿势, expression表情, effect特效, file文件, mood情绪, camera摄影参数")
    private String assetType;

    /** 资产名称 */
    @Excel(name = "资产名称")
    private String assetName;

    /** 特征描述/生成约束 */
    @Excel(name = "特征描述/生成约束")
    private String personalityDesc;

    /** 提示词内容 */
    @Excel(name = "提示词内容")
    private String promptText;

    /** 主图URL（存相对路径，出参拼域名） */
    @Excel(name = "主图URL")
    @MediaUrl
    private String imageUrl;

    /** 来源类型: USER用户创建, OFFICIAL_COPY官方复制, AI_GENERATED AI生成 */
    @Excel(name = "来源类型: USER用户创建, OFFICIAL_COPY官方复制, AI_GENERATED AI生成")
    private String sourceType;

    /** 排序值 */
    @Excel(name = "排序值")
    private Long sortOrder;

    /** 状态: 0正常 1停用 */
    @Excel(name = "状态: 0正常 1停用")
    private String status;

    /** 删除标志: 0存在 1删除 */
    private String delFlag;

}
