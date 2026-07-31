package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 分镜时间轴主对象 aid_storyboard
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_storyboard")
public class AidStoryboard extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID */
    @Excel(name = "剧集ID")
    private Long episodeId;

    /** 来源场景ID(分镜脚本批量生成时记录,NULL表示历史数据或手动创建) */
    @Excel(name = "来源场景ID")
    @TableField(value = "source_scene_id")
    private Long sourceSceneId;

    /** 所属用户ID */
    @Excel(name = "所属用户ID")
    private Long userId;

    /** 分镜序号 */
    @Excel(name = "分镜序号")
    private Long sortOrder;

    /** 分镜标题 */
    @Excel(name = "分镜标题")
    private String title;

    /** 分镜剧本内容(对该镜头的剧情描述，固定不变) */
    private String storyScript;

    /**
     * 分镜台词配音文本(该镜头角色的对话)。
     */
    private String dialogueText;

    /**
     * 所属批次 aid_storyboard_batch.id。
     * 分镜批量任务产出的分镜会带上批次 ID，手动新建的分镜为 NULL。
     * 用于断点续生时按批次定位旧分镜，以及全局二级排序。
     */
    @Excel(name = "所属批次ID")
    @TableField(value = "batch_id")
    private Long batchId;

    /**
     * 来源场次 sceneCode。
     * 分镜来源 location 的 sceneCode，用于全局二级排序：
     * 同剧集多场景按 sceneCode 升序排列能保证剧情时间线正确。
     * 手动新建的分镜为 NULL。
     */
    @Excel(name = "来源场次序号")
    @TableField(value = "source_scene_code")
    private String sourceSceneCode;

    /** 选中的最终分镜图ID */
    @Excel(name = "选中的最终分镜图ID")
    private Long finalImageId;

    /** 选定的最终视频ID */
    @Excel(name = "选定的最终视频ID")
    private Long finalVideoId;

    /** 选定的最终配音ID */
    @Excel(name = "选定的最终配音ID")
    private Long finalAudioId;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /**
     * 分镜脚本扩展参数 JSON。
     * 使用中文 key 存储，便于 DB 直接阅读与前端无翻译消费：
     * 镜号 / 场次序号 / 批内位置 / 画面描述 / 台词 / 动作状态 / 叙事功能 /
     * 时间坐标 / 年代坐标 / 日期坐标 / 气候天象 / 引用信息 /
     * 景别 / 拍摄角度 / 镜头焦距 / 镜头运动 / 构图 / 画面氛围 / 音效
     */
    @TableField(value = "script_params")
    private String scriptParams;

    /**
     * 分镜图生图 prompt（分镜画师智能体输出）。
     * 由 {@code /api/user/storyboard/generate/image-prompt} 链路异步生成，
     * 直接喂给下游图片生成模型作为提示词；LLM 输出已剥 markdown / JSON 包装，
     * 落库为单行可读字符串。
     */
    @TableField(value = "image_prompt")
    private String imagePrompt;

    /**
     * 分镜画师智能体的原始输出（JSON 数组形式，含可能的 markdown 包装）。
     * 仅供排查与回溯用，业务消费请用 {@link #imagePrompt}。
     */
    @TableField(value = "image_prompt_raw")
    private String imagePromptRaw;

    /**
     * 宫格类型（多宫格画师智能体输出，取值「四宫格」/「九宫格」）。
     * 仅 auto_grid 创作模式的宫格画师（{@code aid_storyboard_grid_painter}）会产出；
     * 由其 LLM 输出 JSON 的 {@code grid_type} 字段解析得到，标准漫剧画师为 null。
     * 供切图 / 前端按宫格数渲染等下游使用。
     */
    @TableField(value = "grid_type")
    private String gridType;

    /**
     * 视觉导演输出的视频提示词（纯字符串，约 180 字以内）。
     * 由 {@code /api/user/storyboard/generate/video-prompt} 链路异步生成，
     * 直接喂给下游图生视频模型作为提示词；LLM 输出已剥 markdown / JSON 包装，
     * 落库为单行可读字符串。
     */
    @TableField(value = "video_prompt")
    private String videoPrompt;

    /**
     * 图生方向（漫剧版）视觉导演输出的视频提示词（纯字符串，约 180 字以内）。
     * 与多参方向的 {@link #videoPrompt} 物理隔离：图生方向由
     * {@code biz_category_code=main_storyboard_video_prompt_image} 的智能体产出，
     * 由图生视频出片链路消费。前端手动传入提示词时也直接落本字段。
     */
    @TableField(value = "video_prompt_image")
    private String videoPromptImage;

}
