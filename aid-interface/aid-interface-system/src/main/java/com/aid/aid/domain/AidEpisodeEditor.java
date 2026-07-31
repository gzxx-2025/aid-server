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
 * 剧集视频剪辑与成片最新状态对象 aid_episode_editor
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_episode_editor")
public class AidEpisodeEditor extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属项目ID (关联 aid_comic_project.id) */
    @Excel(name = "所属项目ID (关联 aid_comic_project.id)")
    private Long projectId;

    /** 所属剧集ID (关联 aid_comic_episode.id) */
    @Excel(name = "所属剧集ID (关联 aid_comic_episode.id)")
    private Long episodeId;

    /** 最后修改该记录的用户ID */
    @Excel(name = "最后修改该记录的用户ID")
    private Long userId;

    /** 核心！前端剪辑器的轨道、素材排版、特效等工程配置报文 (覆盖更新) */
    @Excel(name = "核心！前端剪辑器的轨道、素材排版、特效等工程配置报文 (覆盖更新)")
    private String timelineJson;

    /** 最新导出的成片OSS持久化地址（相对路径） */
    @Excel(name = "最新导出的成片OSS持久化地址。方案一：后端合成后回填；方案二：前端合成并上传OSS后，调用接口回填")
    @MediaUrl
    private String finalVideoUrl;

    /**
     * 待审核新成片地址（相对路径）。
     * 已过审内容重新导出时，新成片先落本槽位，final_video_url 保留旧片继续公开展示；
     * 重新审核通过后新片转正（覆盖 final_video_url）、旧片文件删除、本字段清空。
     * 非空即表示「成片已变更待重新审核」，前端据此展示警示。
     */
    @TableField(value = "pending_video_url")
    @MediaUrl
    private String pendingVideoUrl;

    /** 最新成片的预览封面图（相对路径） */
    @Excel(name = "最新成片的预览封面图OSS地址")
    @MediaUrl
    private String coverUrl;

    /** 导出状态: 0=未导出/JSON已修改待重新导出, 1=正在合成中, 2=导出成功, 3=导出失败 */
    @Excel(name = "导出状态: 0=未导出/JSON已修改待重新导出, 1=正在合成中, 2=导出成功, 3=导出失败")
    private Integer exportStatus;

    /** 导出进度百分比(0-100)。方案一：供前端轮询后端获取进度；方案二：前端本地渲染时同步调用接口上报进度，防页面刷新丢失状态 */
    @Excel(name = "导出进度百分比(0-100)。方案一：供前端轮询后端获取进度；方案二：前端本地渲染时同步调用接口上报进度，防页面刷新丢失状态")
    private Integer exportProgress;

    /** 并发控制标识。方案一：存放后端/云端队列任务ID；方案二：可存放前端生成的防重UUID，防止用户多开浏览器窗口同时合成同一个剧集 */
    @Excel(name = "并发控制标识。方案一：存放后端/云端队列任务ID；方案二：可存放前端生成的防重UUID，防止用户多开浏览器窗口同时合成同一个剧集")
    private String exportTaskId;

    /** 导出失败时的错误原因记录，方便排查问题 */
    @Excel(name = "导出失败时的错误原因记录，方便排查问题")
    private String errorMsg;

    /**
     * 最近一次导出的素材指纹（SHA-256 十六进制，64字符）。
     * 由导出入参的分组素材（视频/配音URL与时长、字幕、BGM、分辨率）归一化后计算；
     * 再次导出时指纹一致且上次成功 → 直接复用 final_video_url，不重复合成、不重复扣费。
     */
    @TableField(value = "export_fingerprint")
    private String exportFingerprint;

    /** 删除标志 (0正常 1删除) */
    private String delFlag;

}
