package com.aid.media.dto;

import lombok.Data;

import java.util.Map;

@Data
public class MediaVideoGenerateRequest {

    // 指定模型名称（可选）：为空时走后端默认模型路由策略。
    private String modelName;

    // 项目ID（可选）：用于关联任务到具体项目，列表查询时按项目过滤。
    private Long projectId;

    // 剧集ID（可选）：电影模式为0，剧集模式为真实剧集ID。
    private Long episodeId;

    // 文本提示词（必填）：驱动视频内容语义。
    private String prompt;

    /**
     * 任务存档摘要（可选,业务方覆盖）：用于 aid_media_task.prompt 列存档展示。
     * 当业务方使用「智能体模板 + 动态入参」拼装出的 prompt 体积过大,会突破 MySQL TEXT
     * 列的 64KB 上限触发 Data truncation;此时把 taskPromptDigest 设为「仅含动态入参」
     * 的紧凑版本即可。后端 setPrompt 阶段优先采用 digest 入库,跳过模板正文。
     * 默认 null 时保持原行为(写完整 prompt)。
     */
    private String taskPromptDigest;

    // 首帧/参考图 URL（可选）：用于 image-to-video 场景。
    private String imageUrl;

    // 目标时长（可选，秒）。
    private Integer durationSeconds;

    // 目标画幅比例（可选）：如 16:9、9:16。
    private String aspectRatio;

    // 扩展参数（可选）：用于协议差异字段透传。
    private Map<String, Object> options;

    /**
     * 是否使用音视频直出能力（可选）：Vidu q3 系列音画同出开关。
     * 仅当模型 capability.supportsAudio 为真时才会下发给上游，否则忽略。
     */
    private Boolean audio;

    /**
     * 是否为生成视频添加背景音乐 BGM（可选）。
     * 仅当模型 capability.supportsBgm 为真时才会下发给上游，否则忽略。
     */
    private Boolean bgm;

    /**
     * 音频类型（可选）：all（音效+人声）/speech_only（仅人声）/sound_effect_only（仅音效）。
     * 仅在 audio=true 且取值合法时下发，audio 关闭时忽略。
     */
    private String audioType;

    /**
     * 音色ID（可选）：决定视频中的声音音色。
     * 仅在 audio=true 且模型 capability.supportsVoiceId 为真、且取值非空时下发。
     */
    private String voiceId;

    /**
     * 业务记录主键（可选）：生成成功后通过 {@link com.aid.service.IGenResultCallbackService#fillResultUrl} 回填。
     * 与 {@link #category} 同时有值时才会触发回填。
     */
    private Long recordId;

    /**
     * 回填目标（可选）：{@code asset} 写入资产表，{@code gen_record} 写入抽卡记录表；与 fillResultUrl 的 category 一致。
     */
    private String category;

    /**
     * 业务任务ID（可选）：用于关联触发本媒体任务的业务任务，如 aid_extract_task.id，
     * 参与 requestHash 计算以破除错误幂等复用（父任务循环多个子视频时尤为关键）。
     */
    private Long bizTaskId;

    /** 业务任务类型（可选）：如 storyboard_video_generate，与 bizTaskId 配合定位具体业务表。 */
    private String bizTaskType;

    /**
     * 计费用户ID（可选，与图片链路对齐）：异步线程内 SecurityContext 会丢失，业务调用方在外层
     * 确定 userId 后必须显式透传，否则媒体任务 userId 为空会导致预冻结/结算/退款全部跳过。为空时服务层回退到登录上下文。
     */
    private Long userId;
}
