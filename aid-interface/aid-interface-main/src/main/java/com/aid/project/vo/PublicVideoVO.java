package com.aid.project.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 公开视频VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class PublicVideoVO {

    /** 项目ID */
    private Long id;

    /** 项目名称 */
    private String projectName;

    /** 作者昵称 */
    private String authorNickname;

    /** 项目类型：series剧集 / movie电影 */
    private String projectType;

    /** 项目描述 */
    private String projectDesc;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 已发布剧集数（剧集类型返回过审集数；电影为 null） */
    private Integer episodeCount;

    /** 封面图（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 成片视频地址（电影=项目成片；剧集=第一集成片；出参拼域名） */
    @MediaUrl
    private String finalVideoUrl;

    /** 列表悬停预览视频地址 */
    @MediaUrl
    private String previewVideoUrl;

    /** 展示媒体像素宽度 */
    private Integer mediaWidth;

    /** 展示媒体像素高度 */
    private Integer mediaHeight;

    /** 展示媒体比例 */
    private String aspectRatio;

    /** 比例来源：measured / declared / fallback */
    private String ratioSource;

    /** 是否允许查看已发布流程快照 */
    private Boolean allowPreview;

    /** 是否允许复制已发布流程快照 */
    private Boolean allowCopy;

    /** 当前是否可以查看已发布项目内容 */
    private Boolean canViewProjectContent;

    /** 项目内容不可查看原因码；可查看时为 null */
    private String viewProjectContentReasonCode;

    /** 当前项目是否具备复制资格，不包含访客登录状态 */
    private Boolean canCopyProject;

    /** 项目不可复制原因码；可复制时为 null */
    private String copyProjectReasonCode;

    /** 复制操作是否需要登录 */
    private Boolean copyRequiresLogin;

    /** 复制来源标签；原创项目为空 */
    private String copyLabel;
}
