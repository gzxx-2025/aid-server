package com.aid.banner.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Builder;
import lombok.Data;

/**
 * 首页 Banner - C 端展示 VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class HomeBannerVO
{
    /** 主键ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 简述 */
    private String summary;

    /** 资源类型（image图片 video视频 gif动图），前端据此决定渲染方式 */
    private String bannerType;

    /** 资源地址（出参已拼成完整可访问 URL，OSS/COS/本地由后端透明处理） */
    @MediaUrl
    private String resourceUrl;

    /** 封面地址（出参已拼成完整可访问 URL；视频/动图的首帧海报，前端在资源加载前展示） */
    @MediaUrl
    private String coverUrl;

    /** 跳转类型（none无跳转 external外部链接 internal内部页面） */
    private String linkType;

    /** 外链/跳转地址（linkType=none 时为空） */
    private String linkUrl;

    /** 排序（值越小越靠前） */
    private Integer sortOrder;
}
