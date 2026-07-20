package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 分镜「首尾帧生视频」出片请求 DTO（首尾帧方向；支持单/多镜头批量）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoEdgeGenerateRequest
{
    /** 分镜 ID 列表（必填，至少 1 个；自动去重）。单镜头传 1 个，多镜头传多个。 */
    @NotEmpty(message = "分镜ID不能为空")
    private List<Long> storyboardIds;

    /**
     * 逐镜头首尾图 / 配音映射（必填，需覆盖 {@link #storyboardIds} 中的每个分镜）。
     * 每个元素指定一个分镜的首图(必填:上传 URL 或库内记录 ID)、尾图(可选:上传 URL 或库内记录 ID)与配音参考(可选);
     * 某分镜首图两种来源都缺,该镜头报「请选首帧」。
     */
    private List<EdgeShotItem> items;

    /**
     * 视频模型 modelCode（可选）。
     * 为空时取 {@code main_storyboard_video_edge}（首尾帧出片专用池）内第一个可用模型兜底；
     * 最终模型必须存在、{@code model_type=video}、支持尾帧输入且落在该专用池内。
     */
    private String modelName;

    /**
     * 首尾帧视频提示词（可选，仅单镜头时生效）。
     * 为空时回落 {@code aid_storyboard.video_prompt}；两者皆空抛「请先生成提示词」。
     */
    private String videoPrompt;

    /** 宽高比（可选，如 16:9 / 9:16 / 1:1）；为空按模型默认。 */
    private String aspectRatio;

    /**
     * 清晰度档位（可选，如 540p / 720p / 1080p）。取值须命中模型 {@code capability_json.sizeOptions} 白名单
     * （忽略大小写），否则报「清晰度不支持」；为空按模型 {@code capability_json.defaultSize} 兜底。
     */
    private String resolution;

    /** 目标视频时长（秒，可选）；为空按模型默认。 */
    private Integer durationSeconds;

    /** 生成数量（可选，默认 1，范围 [1,4]）。仅单镜头生效；多镜头每镜头 1 条，传 &gt;1 报错。 */
    private Integer count;

    /** 是否生成音频（可选，仅部分模型支持）。 */
    private Boolean generateAudio;

    /** 用户补充文本（可选，最大 500 字符，超出截断），拼接到提示词之后。 */
    private String userInputText;

    /**
     * 逐镜头首尾图 / 配音项。
     * 首/尾帧来源二选一(同时传则上传 URL 优先)：① 上传图片 URL(`firstImageUrl`/`lastImageUrl`，本站资源，做合法性校验)；
     * ② 库内记录 ID(`firstImageRecordId`/`lastImageRecordId`，须属本分镜)。首帧必填(两种都没给报「请选首帧」);尾帧可选。
     */
    @Data
    public static class EdgeShotItem
    {
        /** 分镜 ID（必填）。 */
        private Long storyboardId;

        /**
         * 首图来源-上传图片 URL（可选；与 {@link #firstImageRecordId} 二选一，本字段优先）。
         * 必须为本站可访问的图片资源（相对路径或本站域名完整 URL），做远程可达 + 图片格式校验，非法报「图片无效」。
         */
        private String firstImageUrl;

        /** 首图来源-库内记录 ID（可选；aid_gen_record.id，须属本分镜），作为视频首帧。 */
        private Long firstImageRecordId;

        /**
         * 尾图来源-上传图片 URL（可选；与 {@link #lastImageRecordId} 二选一，本字段优先）。
         * 传则作尾帧走关键帧动画，不传(且无 {@link #lastImageRecordId})则仅首帧出单帧图生视频；本站图片校验同首图。
         */
        private String lastImageUrl;

        /** 尾图来源-库内记录 ID（可选；aid_gen_record.id，须属本分镜）。 */
        private Long lastImageRecordId;

        /**
         * 配音参考（可选）：用户配音内容音频片段，最多 7 段、每段 ≤ 10 秒。
         * 透传给支持音频输入的视频模型用于配音；超 7 段报「配音最多7个」，单段时长非法 / &gt;10 秒报「配音超10秒」。
         */
        private List<EdgeAudioItem> audios;
    }

    /**
     * 配音参考片段。
     */
    @Data
    public static class EdgeAudioItem
    {
        /** 配音音频完整 URL（必填）。 */
        private String audioUrl;

        /** 配音时长（秒，必填，范围 (0,10]）。 */
        private Integer durationSeconds;
    }
}
