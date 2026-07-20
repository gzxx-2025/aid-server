package com.aid.project.vo;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

/**
 * 用户项目详情VO
 *
 * @author 视觉AID
 */
@Data
@Builder
public class UserProjectVO {

    /** 主键ID */
    private Long id;

    /** 项目名称 */
    private String projectName;

    /** 项目描述 */
    private String projectDesc;

    /** 类型: series剧集, movie电影 */
    private String projectType;

    /** 封面图（出参拼域名） */
    @MediaUrl
    private String coverUrl;

    /** 画面比例(16:9, 9:16等) */
    private String aspectRatio;

    /** 剧本类型(剧情演绎plot, 真人解说monologue) */
    private String scriptType;

    /** 视频风格名称（前端传什么存什么） */
    private String videoStyleType;

    /** 视频风格值字符串（前端传什么存什么） */
    private String videoStyleValue;

    /** 默认生成模式(economy经济, performance性能) */
    private String defaultGenMode;

    /** 默认创作模式(i2v图生视频, multi多参生视频) */
    private String defaultCreationMode;

    /** 状态(0草稿 1制作中 2完成未提交 3审核中 4审核通过 5审核失败) */
    private Integer status;

    /** 状态原因 */
    private String statusReason;

    /** 是否公开 */
    private String isPublic;

    /** 剧集剪辑记录ID（aid_episode_editor.id，仅电影模式项目级成片，episode_id=0），无记录为 null */
    private Long episodeEditorId;

    /** 项目级成片视频地址（仅电影模式，出参自动拼 OSS 域名），未合成为 null；剧集类型项目恒为 null（成片挂在各集上） */
    @MediaUrl
    private String finalVideoUrl;

    /** 待审核新成片地址（仅电影模式，出参自动拼域名）；非空=成片已变更需重新提审（公开侧仍展示 finalVideoUrl 旧片） */
    @MediaUrl
    private String pendingVideoUrl;

    /** 项目级成片导出状态（仅电影模式）：0=未导出/待重新导出，1=合成中，2=导出成功，3=导出失败；无记录为 null */
    private Integer exportStatus;

    /** 剧集总集数（仅剧集类型项目返回，统计未删除分集，无集为 0）；电影类型恒为 null */
    private Long episodeCount;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
