package com.aid.media.constants;

/**
 * Vidu 配音/音频接口（audio-tts / text2audio / timing2audio / lip-sync）的端点路径与请求体参数键登记，供后续配音功能取用。
 */
public final class ViduAudioConstants {

    private ViduAudioConstants() {
    }

    // --- 端点路径（仅供前端展示/DB 种子/参考，运行时一律 base_url + api_suffix）---

    /** 语音合成（同步，返回 file_url，无回调） */
    public static final String PATH_AUDIO_TTS = "/ent/v2/audio-tts";
    /** 文生音频（支持音效 + BGM） */
    public static final String PATH_TEXT2AUDIO = "/ent/v2/text2audio";
    /** 可控文生音效（不支持 BGM，按时间轴控制） */
    public static final String PATH_TIMING2AUDIO = "/ent/v2/timing2audio";
    /** 对口型 */
    public static final String PATH_LIP_SYNC = "/ent/v2/lip-sync";

    // --- audio-tts（语音合成）请求体参数键 ---

    /** 需要合成语音的文本 */
    public static final String TTS_TEXT = "text";
    /** 合成音频的音色ID */
    public static final String TTS_VOICE_SETTING_VOICE_ID = "voice_setting_voice_id";
    /** 语速，默认 1.0，范围 [0.5,2] */
    public static final String TTS_VOICE_SETTING_SPEED = "voice_setting_speed";
    /** 音量，范围 0-10，默认 0 */
    public static final String TTS_VOICE_SETTING_VOLUME = "voice_setting_volume";
    /** 语调，范围 [-12,12]，默认 0 */
    public static final String TTS_VOICE_SETTING_PITCH = "voice_setting_pitch";
    /** 情绪：happy/sad/angry/fearful/disgusted/surprised/calm */
    public static final String TTS_VOICE_SETTING_EMOTION = "voice_setting_emotion";
    /** 多音字发音替换规则列表 */
    public static final String TTS_PRONUNCIATION_DICT_TONE = "pronunciation_dict_tone";
    /** 透传参数 */
    public static final String TTS_PAYLOAD = "payload";

    // --- text2audio（文生音频）请求体参数键 ---

    /** 模型名称，可选值：audio1.0 */
    public static final String TEXT2AUDIO_MODEL = "model";
    /** 文本提示词 */
    public static final String TEXT2AUDIO_PROMPT = "prompt";
    /** 音频时长，默认 10，范围 2~10 秒 */
    public static final String TEXT2AUDIO_DURATION = "duration";
    /** 随机种子 */
    public static final String TEXT2AUDIO_SEED = "seed";

    // --- timing2audio（可控文生音效）请求体参数键 ---

    /** 可控音效参数数组（每项含 from/to/prompt） */
    public static final String TIMING2AUDIO_TIMING_PROMPTS = "timing_prompts";
    /** timing_prompts 子项：事件起始时间（秒） */
    public static final String TIMING2AUDIO_FROM = "from";
    /** timing_prompts 子项：事件结束时间（秒） */
    public static final String TIMING2AUDIO_TO = "to";
    /** timing_prompts 子项：事件音效提示词 */
    public static final String TIMING2AUDIO_PROMPT = "prompt";

    // --- lip-sync（对口型）请求体参数键 ---

    /** 原视频 URL */
    public static final String LIP_SYNC_VIDEO_URL = "video_url";
    /** 音频文件 URL（音频驱动） */
    public static final String LIP_SYNC_AUDIO_URL = "audio_url";
    /** 文本内容（文本驱动） */
    public static final String LIP_SYNC_TEXT = "text";
    /** 语速，默认 1.0，范围 [0.5,2]，仅文字生成时生效 */
    public static final String LIP_SYNC_SPEED = "speed";
    /** 音色ID，仅文字生成时生效 */
    public static final String LIP_SYNC_VOICE_ID = "voice_id";
    /** 人脸参考图 URL（多人脸时指定目标） */
    public static final String LIP_SYNC_REF_PHOTO_URL = "ref_photo_url";
    /** 音量，范围 0-10，默认 0，仅文字生成时生效 */
    public static final String LIP_SYNC_VOLUME = "volume";
}
