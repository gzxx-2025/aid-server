package com.aid.compose.dto.timeline;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 时间轴配音轨元素：该段配音（来自「音画同步」步骤的配音记录），
 * 携带音色完整参数快照（音色/情感/语速/音调等），部分为预留字段，便于重新配音与后续能力开放。
 * 出参恒定：对象永不为 null，无配音时 url 及音色字段=null、durationSeconds=0、音量给默认值。
 *
 * @author 视觉AID
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TimelineVoiceItem {

    /** 来源配音记录ID（aid_audio_record.id），自动初始化时回填，便于溯源与重配 */
    private Long audioRecordId;

    /** 配音音频地址。库内存相对路径，出参拼完整域名；为空 = 该段无配音 */
    private String url;

    /** 配音时长（秒），来自 aid_audio_record.duration_ms 换算；无配音为 0 */
    private Double durationSeconds;

    /** 配音音量（0-100，默认 100） */
    private Integer volume;

    /** 是否静音（默认 false） */
    private Boolean muted;

    /** 配音文本（该段台词，aid_audio_record.tts_text），重配音时直接复用 */
    private String ttsText;

    /** 音色库ID（aid_ai_voice_library.id），重配音时指定音色用 */
    private Long voiceLibraryId;

    /** TTS 模型ID（aid_ai_model.id） */
    private Long voiceModelId;

    /** 厂商侧音色编码（aid_ai_voice_library.voice_code） */
    private String timbreCode;

    /** 音色展示名称（aid_ai_voice_library.voice_name），前端直接展示，如「甜美少女音」 */
    private String voiceName;

    /** 情感编码（预留，音色支持情感时可用，取值来自音色库 emotion_tags；当前为 null） */
    private String emotion;

    /** 语速（预留，0.5~2.0，默认取音色库 default_speed；不支持语速的音色为 null） */
    private BigDecimal speed;

    /** 音调（预留，-12~12，默认取音色库 default_pitch；不支持音调的音色为 null） */
    private BigDecimal pitch;
}
