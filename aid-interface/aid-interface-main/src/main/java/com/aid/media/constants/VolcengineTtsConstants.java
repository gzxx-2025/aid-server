package com.aid.media.constants;

/**
 * 豆包 TTS（火山 openspeech 网关 /api/v3/tts/*）调用相关常量。
 *
 * @author 视觉AID
 */
public final class VolcengineTtsConstants {

    private VolcengineTtsConstants() {
    }

    // --- 协议（与 aid_media_task.protocol / 路由一致）---

    /** 豆包语音合成 2.0 / 1.0 公版 + 声音复刻 2.0/1.0 统一协议 */
    public static final String PROTOCOL_TTS = "volcengine-tts";

    /** supportsModel 匹配关键字（小写） */
    public static final String MODEL_HINT_SEED_TTS = "seed-tts";
    public static final String MODEL_HINT_SEED_ICL = "seed-icl";

    // --- 默认模型 ---

    public static final String DEFAULT_MODEL = "seed-tts-2.0";

    // --- HTTP 路径（openspeech 网关固定）---

    /** 单向流式 HTTP 合成接口（新版，X-Api-Key 鉴权，一次请求流式返回音频分片） */
    public static final String UNIDIRECTIONAL_PATH = "/api/v3/tts/unidirectional";

    public static final int HTTP_TIMEOUT_MS = 30_000;

    /** 流式合成读流超时（合成耗时随文本长度增长，放宽到 120s） */
    public static final int STREAM_HTTP_TIMEOUT_MS = 120_000;

    // --- 鉴权头（豆包 TTS 新版：单个 X-Api-Key）---

    /** 新版单向流式鉴权头：取 provider/模型的 api_key（控制台 API Key 管理获取） */
    public static final String HEADER_API_KEY = "X-Api-Key";
    public static final String HEADER_RESOURCE_ID = "X-Api-Resource-Id";
    public static final String HEADER_REQUEST_ID = "X-Api-Request-Id";

    // --- Resource-Id 取值（capability_json.resourceId；兜底按 model_code 推断）---

    public static final String RESOURCE_ID_SEED_TTS_1_0 = "seed-tts-1.0";
    public static final String RESOURCE_ID_SEED_TTS_2_0 = "seed-tts-2.0";
    public static final String RESOURCE_ID_SEED_ICL_1_0 = "seed-icl-1.0";
    public static final String RESOURCE_ID_SEED_ICL_2_0 = "seed-icl-2.0";

    // --- 音频默认参数 ---

    public static final String DEFAULT_AUDIO_FORMAT = "mp3";
    public static final int DEFAULT_SAMPLE_RATE = 24000;
    public static final int DEFAULT_EMOTION_SCALE = 4;

    /** 情绪值下限（官方 emotion_scale 取值范围 1~5） */
    public static final int EMOTION_SCALE_MIN = 1;

    /** 情绪值上限（官方 emotion_scale 取值范围 1~5） */
    public static final int EMOTION_SCALE_MAX = 5;

    public static final int SPEECH_RATE_MIN = -50;
    public static final int SPEECH_RATE_MAX = 100;
    public static final int LOUDNESS_RATE_MIN = -50;
    public static final int LOUDNESS_RATE_MAX = 100;
    public static final int PITCH_MIN = -12;
    public static final int PITCH_MAX = 12;
    public static final int SILENCE_DURATION_MAX = 30_000;

    // --- 响应字段路径（dot-path，配合 ProviderResponseHelper.readText）---

    public static final String RESP_CODE = "code";
    public static final String RESP_MESSAGE = "message";
    public static final String RESP_TASK_ID = "data.task_id";
    public static final String RESP_TASK_STATUS = "data.task_status";
    public static final String RESP_AUDIO_URL = "data.audio_url";

    /** 单向流式：每帧 data 为一段 base64 音频分片 */
    public static final String RESP_DATA = "data";

    // --- 单向流式分帧 code 语义 ---

    /** 数据分片帧：code=0，data 为本片 base64 音频 */
    public static final int STREAM_CODE_CHUNK = 0;
    /** 流结束帧（成功）：code=20000000，收到即可停止读取 */
    public static final int STREAM_CODE_END = 20_000_000;

    // --- 音频 OSS 落库后缀 / ContentType ---

    public static final String AUDIO_SUFFIX_MP3 = ".mp3";
    public static final String AUDIO_SUFFIX_WAV = ".wav";
    public static final String AUDIO_SUFFIX_OGG = ".ogg";
    public static final String AUDIO_SUFFIX_PCM = ".pcm";
    public static final String AUDIO_CONTENT_TYPE_MP3 = "audio/mpeg";
    public static final String AUDIO_CONTENT_TYPE_WAV = "audio/wav";
    public static final String AUDIO_CONTENT_TYPE_OGG = "audio/ogg";
    public static final String AUDIO_CONTENT_TYPE_PCM = "audio/L16";

    // --- 任务状态（豆包 TTS 协议定义：1=running, 2=success, 3=failure）---

    public static final int VENDOR_TASK_STATUS_RUNNING = 1;
    public static final int VENDOR_TASK_STATUS_SUCCESS = 2;
    public static final int VENDOR_TASK_STATUS_FAILURE = 3;

    /** 豆包 TTS 成功 code（submit/query 共用）：20000000 */
    public static final int VENDOR_CODE_OK = 20_000_000;

    // --- 归一化状态（与 MediaTaskStatus 字符串一致，避免跨模块 import 循环）---

    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    // --- 请求 body 字段名 ---

    public static final String FIELD_USER = "user";
    public static final String FIELD_USER_UID = "uid";
    public static final String FIELD_UNIQUE_ID = "unique_id";
    public static final String FIELD_NAMESPACE = "namespace";
    public static final String FIELD_REQ_PARAMS = "req_params";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_SSML = "ssml";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_SPEAKER = "speaker";
    public static final String FIELD_AUDIO_PARAMS = "audio_params";
    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_SAMPLE_RATE = "sample_rate";
    public static final String FIELD_EMOTION = "emotion";
    public static final String FIELD_EMOTION_SCALE = "emotion_scale";
    public static final String FIELD_SPEECH_RATE = "speech_rate";
    public static final String FIELD_LOUDNESS_RATE = "loudness_rate";
    public static final String FIELD_ENABLE_TIMESTAMP = "enable_timestamp";
    /** 新版单向流式：字级时间戳开关字段名（audio_params 内） */
    public static final String FIELD_ENABLE_SUBTITLE = "enable_subtitle";
    public static final String FIELD_ADDITIONS = "additions";
    public static final String FIELD_ADDITIONS_SILENCE_DURATION = "silence_duration";
    public static final String FIELD_ADDITIONS_POST_PROCESS = "post_process";
    public static final String FIELD_ADDITIONS_POST_PROCESS_PITCH = "pitch";
    /** 语音指令（req_params 直属数组，2.0 公版音色情感变化按官方「指令遵循」协议使用） */
    public static final String FIELD_CONTEXT_TEXTS = "context_texts";
    public static final String FIELD_TASK_ID = "task_id";

    /** 2.0 公版音色情感语音指令模板（%s 为情感中文名，编码未收录时回退原编码） */
    public static final String EMOTION_INSTRUCTION_TEMPLATE = "请用%s的情感语气朗读这段话";

    public static final String NAMESPACE_DEFAULT = "BidirectionalTTS";

    // --- capability_json 中的键名 ---

    public static final String CAPABILITY_KEY_RESOURCE_ID = "resourceId";
    public static final String CAPABILITY_KEY_DEFAULT_SUB_MODEL = "defaultSubModel";

    // --- options 透传键 ---

    public static final String OPTIONS_SSML = "ssml";
    public static final String OPTIONS_SILENCE_DURATION_MS = "silenceDurationMs";
    public static final String OPTIONS_SUB_MODEL = "subModel";

    // --- 日志 ---

    public static final int LOG_TEXT_ABBREV_MAX = 80;

    // --- 用户可见错误文案（≤ 6 字）---

    public static final String ERR_APP_ID_EMPTY = "鉴权缺失";
    public static final String ERR_VOICE_EMPTY = "音色无效";
    public static final String ERR_TEXT_EMPTY = "文本为空";
    public static final String ERR_TTS_SUBMIT = "配音失败";
    public static final String ERR_TTS_QUERY = "查询失败";
    /** 流式合成失败（网络/上游错误帧/上传失败统一文案） */
    public static final String ERR_TTS_STREAM = "合成失败";
    /** 流式合成未返回任何音频分片 */
    public static final String ERR_TTS_NO_AUDIO = "合成为空";
}
