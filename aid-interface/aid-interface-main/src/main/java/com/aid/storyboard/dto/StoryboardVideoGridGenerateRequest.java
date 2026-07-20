package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 分镜「宫格生视频」出片请求 DTO（宫格方向，auto_grid 专业版；支持单/多镜头批量）。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoGridGenerateRequest
{
    /** 分镜 ID 列表（必填，至少 1 个；自动去重）。单镜头传 1 个，多镜头传多个。 */
    @NotEmpty(message = "分镜ID不能为空")
    private List<Long> storyboardIds;

    /**
     * 视频模型 modelCode（可选）。
     * 为空时取 {@code main_storyboard_video_grid}（宫格出片专用池）内第一个可用模型兜底；
     * 最终模型必须存在、{@code model_type=video}、支持图片输入且落在该池内。
     */
    private String modelName;

    /**
     * 宫格视频提示词（可选，仅单镜头时生效）。
     * 为空时回落 {@code aid_storyboard.video_prompt_image}；两者皆空抛「请先生成提示词」。
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
}
