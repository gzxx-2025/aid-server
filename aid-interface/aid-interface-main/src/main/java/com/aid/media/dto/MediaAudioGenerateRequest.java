package com.aid.media.dto;

import lombok.Data;

import java.util.Map;

/**
 * 媒体语音合成（TTS）请求 DTO。
 */
@Data
public class MediaAudioGenerateRequest {

    /** 指定模型名称（可选，对应 aid_ai_model.model_code，如 seed-tts-2.0 / seed-tts-1.0 / seed-icl-2.0）；为空时走 AUDIO 类型默认模型 */
    private String modelName;

    /** 项目ID（可选），用于按项目归类任务 */
    private Long projectId;

    /** 剧集ID（可选），电影模式为 0 */
    private Long episodeId;

    /** 待合成的文本（必填），即 TTS text */
    private String ttsText;

    /** 音色编码（必填），对应 aid_ai_voice_library.voice_code；透传给厂商 req_params.speaker */
    private String voiceCode;

    /** 语言标识（可选，如 zh-CN / en-US / ja-JP），主要用于日志排查；豆包 TTS 不强制要求 */
    private String language;

    /** 情感（可选，如 happy / sad / angry）；仅对支持情感的音色生效 */
    private String emotion;

    /** 情感强度（可选，1~5，默认 4），与 emotion 配合使用 */
    private Integer emotionScale;

    /** 语速（可选，[-50,100]，0 为默认 1.0x，100 为 2.0x，-50 为 0.5x） */
    private Integer speechRate;

    /** 音量（可选，[-50,100]，0 为默认 1.0x） */
    private Integer loudnessRate;

    /** 音调（可选，[-12,12]，0 为默认） */
    private Integer pitch;

    /** 音频编码格式（可选，mp3 / wav / pcm / ogg_opus），默认 mp3 */
    private String audioFormat;

    /** 音频采样率（可选，8000/16000/22050/24000/32000/44100/48000），默认 24000 */
    private Integer sampleRate;

    /** 是否启用逐句时间戳（可选，默认 false） */
    private Boolean enableTimestamp;

    /** 扩展参数（可选，用于协议差异透传）：ssml、silenceDurationMs、subModel 等，provider 不识别时忽略 */
    private Map<String, Object> options;

    /** 业务任务ID（可选），用于关联触发本音频任务的业务记录（如 aid_audio_record.id） */
    private Long bizTaskId;

    /** 业务任务类型（可选，如 audio_record / storyboard_audio），与 bizTaskId 配合定位业务表 */
    private String bizTaskType;

    /** 业务记录主键（可选），成功后触发 GenResultCallback 回填 */
    private Long recordId;

    /** 回填目标（可选，audio_record / gen_record 等） */
    private String category;

    /** 计费用户ID（可选）：异步线程 SecurityContext 丢失时用，为空再回退登录上下文 */
    private Long userId;

    /** 试听模式（可选，默认 false）：true 时同步返回 base64 音频、不上传对象存储、不落库 */
    private boolean previewMode;
}
