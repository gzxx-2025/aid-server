package com.aid.media.constants;

/**
 * MiniMax TTS（openapi.minimaxi.com）异步长文本语音合成相关常量。
 *
 * @author 视觉AID
 */
public final class MinimaxTtsConstants
{
    private MinimaxTtsConstants()
    {
    }
    /** MiniMax TTS 统一协议标识（与 aid_media_task.protocol 一致） */
    public static final String PROTOCOL_TTS = "minimax-tts";

    /** supportsModel 关键字：speech-2.* / speech-02-* 全部命中 */
    public static final String MODEL_HINT_SPEECH = "speech-";

    /** 默认模型 */
    public static final String DEFAULT_MODEL = "speech-2.8-hd";
    public static final String SUBMIT_PATH = "/v1/t2a_async_v2";
    public static final String QUERY_PATH = "/v1/query/t2a_async_query";
    public static final String FILE_RETRIEVE_PATH = "/v1/files/retrieve";
    public static final String VOICE_LIST_PATH = "/v1/get_voice";

    /** 同步非流式语音合成（新版统一改用此接口）：一次请求直返 hex 音频 */
    public static final String T2A_SYNC_PATH = "/v1/t2a_v2";

    /** 同步接口 text 官方上限：长度限制小于 10000 字符（异步长文本 5 万上限不适用于同步） */
    public static final int SYNC_TEXT_MAX_LENGTH = 10_000;

    /** 批量配音单条文本上限：MiniMax 音色单条配音文本最多 500 字符 */
    public static final int BATCH_TEXT_MAX_LENGTH = 500;

    public static final int HTTP_TIMEOUT_MS = 30_000;
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_LANGUAGE_BOOST = "language_boost";
    public static final String FIELD_AIGC_WATERMARK = "aigc_watermark";

    public static final String FIELD_VOICE_SETTING = "voice_setting";
    public static final String FIELD_VOICE_ID = "voice_id";
    public static final String FIELD_SPEED = "speed";
    public static final String FIELD_VOL = "vol";
    public static final String FIELD_PITCH = "pitch";
    public static final String FIELD_EMOTION = "emotion";
    public static final String FIELD_ENGLISH_NORMALIZATION = "english_normalization";

    public static final String FIELD_AUDIO_SETTING = "audio_setting";
    public static final String FIELD_AUDIO_SAMPLE_RATE = "audio_sample_rate";
    public static final String FIELD_BITRATE = "bitrate";
    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_CHANNEL = "channel";

    /** 同步接口：是否流式（统一传 false，走非流式同步） */
    public static final String FIELD_STREAM = "stream";
    /** 同步接口：输出形态 hex / url（统一用 hex，本地解码上传 OSS） */
    public static final String FIELD_OUTPUT_FORMAT = "output_format";
    /** output_format 取值：hex（音频以 hex 字符串返回） */
    public static final String OUTPUT_FORMAT_HEX = "hex";

    public static final String FIELD_PRONUNCIATION_DICT = "pronunciation_dict";
    public static final String FIELD_TONE = "tone";

    public static final String FIELD_VOICE_MODIFY = "voice_modify";
    public static final String FIELD_VOICE_MODIFY_PITCH = "pitch";
    public static final String FIELD_VOICE_MODIFY_INTENSITY = "intensity";
    public static final String FIELD_VOICE_MODIFY_TIMBRE = "timbre";
    public static final String FIELD_VOICE_MODIFY_SOUND_EFFECTS = "sound_effects";
    public static final String RESP_TASK_ID = "task_id";
    public static final String RESP_FILE_ID = "file_id";
    public static final String RESP_STATUS = "status";
    public static final String RESP_DOWNLOAD_URL = "file.download_url";
    public static final String RESP_BASE_STATUS_CODE = "base_resp.status_code";
    public static final String RESP_BASE_STATUS_MSG = "base_resp.status_msg";

    /** 同步接口：hex 编码音频字段（dot-path） */
    public static final String RESP_AUDIO = "data.audio";

    /** 同步接口：合成音频时长毫秒（dot-path，官方 extra_info.audio_length） */
    public static final String RESP_EXTRA_AUDIO_LENGTH = "extra_info.audio_length";

    /** 成功返回 code（base_resp.status_code == 0） */
    public static final int VENDOR_CODE_OK = 0;

    // 任务状态字符串（来自 query 接口）
    public static final String VENDOR_STATUS_PROCESSING = "Processing";
    public static final String VENDOR_STATUS_SUCCESS = "Success";
    public static final String VENDOR_STATUS_FAILED = "Failed";
    public static final String VENDOR_STATUS_EXPIRED = "Expired";

    // 归一化状态（与 MediaTaskStatus 字符串一致）
    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";
    public static final String DEFAULT_AUDIO_FORMAT = "mp3";
    public static final int DEFAULT_SAMPLE_RATE = 32_000;
    public static final int DEFAULT_BITRATE = 128_000;
    /** 声道数官方默认 1（单声道）；TTS 配音无双声道收益，对齐官方默认减小音频体积 */
    public static final int DEFAULT_CHANNEL = 1;

    // 入参量纲（豆包系 rate [-50,100] → MiniMax speed/vol 乘数）
    public static final double MINIMAX_SPEED_MIN = 0.5;
    public static final double MINIMAX_SPEED_MAX = 2.0;
    public static final double MINIMAX_VOL_MIN = 0.01;
    public static final double MINIMAX_VOL_MAX = 10.0;

    public static final int PITCH_MIN = -12;
    public static final int PITCH_MAX = 12;
    public static final String AUDIO_SUFFIX_MP3 = ".mp3";
    public static final String AUDIO_SUFFIX_WAV = ".wav";
    public static final String AUDIO_SUFFIX_PCM = ".pcm";
    public static final String AUDIO_SUFFIX_FLAC = ".flac";
    public static final String AUDIO_SUFFIX_OPUS = ".opus";
    public static final String AUDIO_CONTENT_TYPE_MP3 = "audio/mpeg";
    public static final String AUDIO_CONTENT_TYPE_WAV = "audio/wav";
    public static final String AUDIO_CONTENT_TYPE_PCM = "audio/L16";
    public static final String AUDIO_CONTENT_TYPE_FLAC = "audio/flac";
    public static final String AUDIO_CONTENT_TYPE_OPUS = "audio/ogg";
    /** options.languageBoost —— 语言加强（auto / Chinese / English / ... 40 种） */
    public static final String OPTIONS_LANGUAGE_BOOST = "languageBoost";
    /** options.englishNormalization —— boolean，开启后数字阅读效果更好 */
    public static final String OPTIONS_ENGLISH_NORMALIZATION = "englishNormalization";
    /** options.aigcWatermark —— boolean，合成音频末尾加 AIGC 节奏标识 */
    public static final String OPTIONS_AIGC_WATERMARK = "aigcWatermark";
    /** options.channel —— 1 / 2 声道；默认 2 */
    public static final String OPTIONS_CHANNEL = "channel";
    /** options.bitrate —— mp3 bitrate；默认 128000 */
    public static final String OPTIONS_BITRATE = "bitrate";
    /** options.pronunciationTone —— List&lt;String&gt;，如 ["燕少飞/(yan4)(shao3)(fei1)"] */
    public static final String OPTIONS_PRONUNCIATION_TONE = "pronunciationTone";
    /** options.soundEffect —— spacious_echo / auditorium_echo / lofi_telephone / robotic */
    public static final String OPTIONS_SOUND_EFFECT = "soundEffect";
    /** options.voiceModifyIntensity —— [-100,100] */
    public static final String OPTIONS_VOICE_MODIFY_INTENSITY = "voiceModifyIntensity";
    /** options.voiceModifyTimbre —— [-100,100] */
    public static final String OPTIONS_VOICE_MODIFY_TIMBRE = "voiceModifyTimbre";
    public static final String CAPABILITY_KEY_PROVIDER = "provider";
    public static final String CAPABILITY_VALUE_PROVIDER = "minimax";
    public static final String CAPABILITY_KEY_EMOTIONS = "emotions";
    public static final String CAPABILITY_KEY_INLINE_SSML = "inlineSsmlSupported";
    public static final String ERR_APP_KEY_EMPTY = "鉴权缺失";
    public static final String ERR_TEXT_EMPTY = "文本为空";
    public static final String ERR_VOICE_EMPTY = "音色无效";
    public static final String ERR_TTS_SUBMIT = "配音失败";
    public static final String ERR_TTS_QUERY = "查询失败";
    public static final String ERR_FILE_RETRIEVE = "下载失败";
    public static final String ERR_VOICE_SYNC = "同步失败";
    /** 同步合成未返回音频数据 */
    public static final String ERR_TTS_NO_AUDIO = "合成为空";
    public static final int LOG_TEXT_ABBREV_MAX = 80;
}
