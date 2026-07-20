package com.aid.storyboard.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起配音任务请求DTO
 *
 * @author 视觉AID
 */
@Data
public class GenerateAudioRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long storyboardId;

    /** 需要配音的文字 */
    @NotBlank(message = "配音文字不能为空")
    private String ttsText;

    /**
     * 配音大模型ID（兼容老入参）。
     * 新链路推荐传 {@link #voiceLibraryId} 由后端反查；老入参仍支持，此时 {@link #voiceLibraryId} 可为空。
     */
    private Long voiceModelId;

    /**
     * 音色编码（兼容老入参，等价于厂商侧 voice_code）。
     * 新链路推荐用 {@link #voiceLibraryId} 反查；两者同时存在时以 voiceLibraryId 为准。
     */
    private String timbreCode;

    /**
     * 音色库记录ID。
     * 若传入该字段，后端优先按 aid_ai_voice_library 反查并使用其 model_id / voice_code，
     * 同时会按音色库的启用 / 下架时间做强校验。
     */
    private Long voiceLibraryId;

    /** 情感（可选，如 happy / sad），仅在音色支持情感时生效 */
    private String emotion;

    /** 情感强度（可选，1~5，默认 4） */
    private Integer emotionScale;

    /** 语速（可选，[-50,100]） */
    private Integer speechRate;

    /** 音量（可选，[-50,100]） */
    private Integer loudnessRate;

    /** 音调（可选，[-12,12]） */
    private Integer pitch;

    /** 音频编码格式（可选，默认 mp3） */
    private String audioFormat;

    /** 音频采样率（可选，默认 24000） */
    private Integer sampleRate;
    /**
     * 语言加强（MiniMax：`language_boost`）：
     * 可选值 `auto` / `Chinese` / `Chinese,Yue` / `English` / `Japanese` / ... 40 种语种；
     * 传入后透传到 MiniMax，未传默认 `auto`；豆包 TTS 不识别会忽略。
     */
    private String languageBoost;

    /**
     * 英语文本规范化（MiniMax：`voice_setting.english_normalization`）。
     * 数字 / 缩写 / 单位等数字阅读场景开启后效果更好，会略增延迟；豆包忽略。
     */
    private Boolean englishNormalization;

    /**
     * AIGC 水印（MiniMax：`aigc_watermark`）。
     * 在音频末尾追加 AIGC 节奏标识；非流式合成生效；豆包忽略。
     */
    private Boolean aigcWatermark;

    /** 声道数（MiniMax：`audio_setting.channel`，1=单 / 2=双，默认 2）；豆包忽略 */
    private Integer channel;

    /** 比特率（MiniMax：`audio_setting.bitrate`，仅对 mp3 生效，默认 128000）；豆包忽略 */
    private Integer bitrate;

    /**
     * 发音词典 tone（MiniMax：`pronunciation_dict.tone`）。
     * 示例：`["燕少飞/(yan4)(shao3)(fei1)", "omg/oh my god"]`。
     * 传入后透传到 MiniMax；豆包忽略。
     */
    private List<String> pronunciationTone;

    /**
     * 音效（MiniMax：`voice_modify.sound_effects`）。
     * 可选 `spacious_echo` / `auditorium_echo` / `lofi_telephone` / `robotic`；
     * 单次仅能选一种；豆包忽略。
     */
    private String soundEffect;

    /**
     * 声音强度调整（MiniMax：`voice_modify.intensity`，范围 [-100, 100]）；豆包忽略
     */
    private Integer voiceModifyIntensity;

    /**
     * 音色亮度调整（MiniMax：`voice_modify.timbre`，范围 [-100, 100]）；豆包忽略
     */
    private Integer voiceModifyTimbre;

    /**
     * 声音音高调整（MiniMax：`voice_modify.pitch`，范围 [-100, 100]）；
     * 与顶层 {@link #pitch}（[-12,12] 的语调）语义不同；豆包忽略
     */
    private Integer voiceModifyPitch;
}
