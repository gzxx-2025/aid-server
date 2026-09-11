package com.aid.aid.domain.vo;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 公开广场项目VO（项目联表作者昵称，供C端公开列表组装使用）
 *
 * @author 视觉AID
 */
@Data
public class AidPublicProjectVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 项目ID */
    private Long id;

    /** 所属用户ID */
    private Long userId;

    /** 作者昵称 */
    private String authorNickname;

    /** 项目名称 */
    private String projectName;

    /** 项目类型：series剧集 / movie电影 */
    private String projectType;

    /** 项目描述 */
    private String projectDesc;

    /** 封面图（相对路径，出参由业务VO拼域名） */
    private String coverUrl;

    /** 项目声明的画面比例 */
    private String aspectRatio;

    /** 项目状态（3=审核中/4=审核通过；审核中项目仅当存在待审新片时展示旧成片） */
    private Integer status;

    /** 是否允许查看已发布流程快照：0否 1是 */
    private String allowPreview;

    /** 是否允许复制已发布流程快照：0否 1是 */
    private String allowCopy;

    /** 当前已审核发布快照ID */
    private Long publishedSnapshotId;

    /** 复制来源标签；原创项目为空 */
    private String copyLabel;

    /** 发布时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;
}
