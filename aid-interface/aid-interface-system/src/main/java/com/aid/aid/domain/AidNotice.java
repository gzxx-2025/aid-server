package com.aid.aid.domain;

import java.io.Serializable;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * C 端公告对象 aid_notice
 *
 * 用于配置 C 端公告：列表展示标题/描述/图片等摘要，详情展示完整内容；
 * 支持图片公告与视频公告（is_video=1 时 video_url 为视频地址、image_url 作为封面）。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_notice")
public class AidNotice extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 公告标题 */
    @Excel(name = "公告标题")
    private String title;

    /** 公告描述（摘要/副标题） */
    @Excel(name = "公告描述")
    private String description;

    /** 图片地址（存相对路径，出参由 @MediaUrl 自动拼域名；视频公告时作为封面图） */
    @Excel(name = "图片地址")
    @MediaUrl
    private String imageUrl;

    /** 是否视频公告（0否 1是） */
    @Excel(name = "是否视频", readConverterExp = "0=否,1=是")
    private String isVideo;

    /** 视频地址（存相对路径，出参由 @MediaUrl 自动拼域名；仅 is_video=1 时有值） */
    @Excel(name = "视频地址")
    @MediaUrl
    private String videoUrl;

    /** 公告完整内容（富文本/长文本） */
    @Excel(name = "公告内容")
    private String content;

    /** 公告类型（system系统公告 activity活动公告 update更新公告） */
    @Excel(name = "公告类型", readConverterExp = "system=系统公告,activity=活动公告,update=更新公告")
    private String noticeType;

    /** 是否置顶（0否 1是） */
    @Excel(name = "是否置顶", readConverterExp = "0=否,1=是")
    private String isTop;

    /** 排序（值越小越靠前） */
    @Excel(name = "排序")
    private Integer sortOrder;

    /** 浏览次数 */
    @Excel(name = "浏览次数")
    private Long viewCount;

    /** 状态（0显示 1隐藏） */
    @Excel(name = "状态", readConverterExp = "0=显示,1=隐藏")
    private String status;

    /** 发布时间 */
    @Excel(name = "发布时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 生效开始时间（为空表示不限制） */
    @Excel(name = "生效开始时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 生效结束时间（为空表示不限制） */
    @Excel(name = "生效结束时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;
}
