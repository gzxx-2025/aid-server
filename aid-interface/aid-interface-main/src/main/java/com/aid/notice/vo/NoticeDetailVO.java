package com.aid.notice.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;

import lombok.Builder;
import lombok.Data;

/**
 * 公告详情 VO（C 端，含完整内容）
 *
 * @author 视觉AID
 */
@Data
@Builder
public class NoticeDetailVO
{
    /** 主键ID */
    private Long id;

    /** 公告标题 */
    private String title;

    /** 公告描述（摘要/副标题） */
    private String description;

    /** 图片完整可访问地址（视频公告时为封面图）；未配置时为空 */
    @MediaUrl
    private String imageUrl;

    /** 是否视频公告：true-视频（videoUrl 有值），false-图片/纯文本 */
    private Boolean isVideo;

    /** 视频完整可访问地址；仅 isVideo=true 时有值 */
    @MediaUrl
    private String videoUrl;

    /** 公告完整内容（富文本 HTML） */
    private String content;

    /** 公告类型（system系统公告 activity活动公告 update更新公告） */
    private String noticeType;

    /** 是否置顶：true-置顶展示 */
    private Boolean isTop;

    /** 浏览次数（已含本次浏览） */
    private Long viewCount;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
}
