package com.aid.storyboard.dto;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 分镜图生视频请求 DTO（多参方向，{@code POST /api/user/storyboard/generate/video}），支持单/多镜头批量。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoGenerateRequest
{
    /** 分镜 ID 列表（必填，至少 1 个，自动去重）。 */
    @NotEmpty(message = "分镜ID不能为空")
    private List<Long> storyboardIds;

    /**
     * 视频模型 modelCode（可选）。为空取 {@code main_storyboard_video} 池内首个可用模型兜底；
     * 最终模型须存在、{@code model_type=video} 且在该池内。
     */
    private String modelName;

    /**
     * 图生视频提示词（可选，仅单镜头生效）。为空回落 {@code aid_storyboard.video_prompt}，
     * 可含 {@code @图片N[name]} 占位（下发前清洗为裸引用「图片N」，按 N 顺序解析为真实参考图 URL）。
     */
    private String videoPrompt;

    /** 首帧/垫图来源记录 ID（可选，仅单镜头生效，aid_gen_record.id）。为空回落 {@code aid_storyboard.final_image_id}。 */
    private Long baseImageRecordId;

    /** 宽高比（可选，如 16:9 / 1:1）。为空按模型 {@code default_aspect_ratio} 兜底（仅模型支持比例时生效）。 */
    private String aspectRatio;

    /**
     * 清晰度档位（可选，如 540p / 720p / 1080p）。取值须命中模型 {@code capability_json.sizeOptions} 白名单
     * （忽略大小写），否则报「清晰度不支持」；为空按模型 {@code capability_json.defaultSize} 兜底。
     */
    private String resolution;

    /** 目标视频时长（秒，可选）。为空按模型 {@code default_duration_seconds} 兜底（仅模型支持时长时生效）。 */
    private Integer durationSeconds;

    /**
     * 生成数量（可选，默认 1，范围 [1,4]）。仅单镜头生效，多镜头强制每镜头 1 条。
     * 父任务按数量循环单视频子任务，各自独立计费 / 落 OSS / 写一行 {@code aid_gen_record}。
     */
    private Integer count;

    /**
     * 临时参考图映射（可选，仅单镜头生效）：key = 提示词中 {@code @图片N[name]} 的 name，value = 外部图片完整 URL。
     * 命中的 name 直接用该 URL 作第 N 张参考图（不入库、不做库内校验），未命中的仍回落
     * {@code aid_role_prop_scene_form_image} 库内解析；URL 须合法可达，否则报"图片无效"。key/value 会 trim 归一化。
     * 临时参考图不写入任务快照，不保证续生可复原，按"单次任务、失败需重提"语义使用；需稳定可续生请走资产库。
     */
    private Map<String, String> referenceOverrides;

    /** 是否生成音频（可选，仅部分模型如 Seedance 支持）。透传 {@code options.generate_audio}，不支持则忽略。 */
    private Boolean generateAudio;

    /** 用户补充文本（可选，最大 500 字符）。拼接到 video_prompt 之后，前缀"用户补充："，用于临时微调。 */
    private String userInputText;
}
