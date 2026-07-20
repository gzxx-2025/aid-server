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
 * 首页 Banner 配置对象 aid_home_banner
 *
 * 用于配置首页轮播位内容，资源类型支持图片 / 视频 / 动图，可挂载外链跳转。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_home_banner")
public class AidHomeBanner extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 标题 */
    @Excel(name = "标题")
    private String title;

    /** 简述 */
    @Excel(name = "简述")
    private String summary;

    /** 资源类型（image图片 video视频 gif动图） */
    @Excel(name = "资源类型", readConverterExp = "image=图片,video=视频,gif=动图")
    private String bannerType;

    /** 资源地址（存相对路径，出参由 @MediaUrl 自动拼 OSS/COS/本地域名） */
    @Excel(name = "资源地址")
    @MediaUrl
    private String resourceUrl;

    /** 封面地址（存相对路径，出参由 @MediaUrl 自动拼 OSS/COS/本地域名；视频/动图的首帧海报） */
    @Excel(name = "封面地址")
    @MediaUrl
    private String coverUrl;

    /** 外链地址（点击 Banner 后的跳转链接） */
    @Excel(name = "外链地址")
    private String linkUrl;

    /** 跳转类型（none无跳转 external外部链接 internal内部页面） */
    @Excel(name = "跳转类型", readConverterExp = "none=无跳转,external=外部链接,internal=内部页面")
    private String linkType;

    /** 排序（值越小越靠前） */
    @Excel(name = "排序")
    private Integer sortOrder;

    /** 状态（0显示 1隐藏） */
    @Excel(name = "状态", readConverterExp = "0=显示,1=隐藏")
    private String status;

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
