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
 * 剧集信息对象 aid_comic_episode
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_comic_episode")
public class AidComicEpisode extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属项目ID */
    @Excel(name = "所属项目ID")
    private Long projectId;

    /** 第几集 */
    @Excel(name = "第几集")
    private Long episodeNo;

    /** 单集标题 */
    @Excel(name = "单集标题")
    private String comicTitle;

    /** 待审核单集标题；审核通过后原子替换 comic_title */
    private String pendingComicTitle;

    /** 单集描述 */
    @Excel(name = "单集描述")
    private String comicDesc;

    /** 待审核单集描述；审核通过后原子替换 comic_desc */
    private String pendingComicDesc;

    /** 单集封面图 */
    @Excel(name = "单集封面图")
    @MediaUrl
    private String comicCoverUrl;

    /** 待审核单集封面；审核通过后原子替换 comic_cover_url */
    @MediaUrl
    private String pendingComicCoverUrl;

    /** 所属用户ID */
    @Excel(name = "所属用户ID")
    private Long userId;

    /** 生成模式(economy, performance) */
    @Excel(name = "生成模式(economy, performance)")
    private String genMode;

    /** 创作模式(i2v, multi) */
    @Excel(name = "创作模式(i2v, multi)")
    private String creationMode;

    /**
     * 当前步骤 (1项目配置 2剧本创作 3素材准备 4分镜设计 5视频生成 6音画同步 7成品预览)。
     */
    @Excel(name = "当前步骤")
    private Integer currentStep;

    /** 状态(0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败) */
    @Excel(name = "状态(0草稿 1制作中 2完成未审核 3审核中 4审核通过 5审核失败)")
    private Integer status;

    /** 状态原因 */
    @Excel(name = "状态原因")
    private String statusReason;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

}
