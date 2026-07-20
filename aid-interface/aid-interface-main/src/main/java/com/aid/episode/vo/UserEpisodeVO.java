package com.aid.episode.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 用户剧集详情VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class UserEpisodeVO {

    /** 主键ID */
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 第几集 */
    private Long episodeNo;

    /** 单集标题 */
    private String comicTitle;

    /** 单集描述 */
    private String comicDesc;

    /** 单集封面图 */
    private String comicCoverUrl;

    /** 画面比例 */
    private String aspectRatio;

    /** 剧本类型 */
    private String scriptType;

    /** 视频风格（来自aid_comic_asset中asset_type为style的asset_name） */
    private String videoStyleType;

    /** 视频风格值 */
    private String videoStyleValue;

    /** 生成模式 */
    private String genMode;

    /** 创作模式 */
    private String creationMode;

    /** 状态 */
    private Integer status;

    /** 状态原因 */
    private String statusReason;

    /** 剧集剪辑记录ID（aid_episode_editor.id），无剪辑记录为 null；可直接用于导出/进度查询接口 */
    private Long episodeEditorId;

    /** 该集最新成片视频地址（出参自动拼 OSS 域名），未合成为 null */
    @MediaUrl
    private String finalVideoUrl;

    /** 待审核新成片地址（出参自动拼域名）；非空=成片已变更需重新提审（公开侧仍展示 finalVideoUrl 旧片） */
    @MediaUrl
    private String pendingVideoUrl;

    /** 成片导出状态：0=未导出/待重新导出，1=合成中，2=导出成功，3=导出失败；无剪辑记录为 null */
    private Integer exportStatus;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
