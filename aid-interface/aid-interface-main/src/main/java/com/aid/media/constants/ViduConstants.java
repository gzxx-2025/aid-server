package com.aid.media.constants;

import java.util.Set;

/**
 * Vidu 图片/视频 API 的路径、鉴权、请求体字段、options 控制键与 JSON 路径片段。
 * 协议字段 {@link #PROTOCOL_IMAGE}、{@link #PROTOCOL_VIDEO} 与库表及 Vidu*ProviderClient 一致。
 */
public final class ViduConstants {

    private ViduConstants() {
    }

    // --- HTTP ---

    /** Vidu：Authorization 取值前缀，完整为 {@code Token } + apiKey */
    public static final String AUTH_TOKEN_PREFIX = "Token ";
    /** 兼容部分网关：独立 Token 请求头，值为 apiKey */
    public static final String HEADER_TOKEN = "Token";
    public static final int HTTP_TIMEOUT_MS = 120_000;

    // --- 协议与默认模型（与任务表 protocol 一致）---

    public static final String PROTOCOL_IMAGE = "vidu-image";
    public static final String PROTOCOL_VIDEO = "vidu-video";

    /** aid_ai_provider.provider_code 约定：Vidu 服务商登记为该 code，调度层按此精确路由 */
    public static final String PROVIDER_CODE = "vidu";

    public static final String DEFAULT_IMAGE_MODEL_CODE = "viduq2";

    // --- 归一化任务状态（与平台轮询状态机一致）---

    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    /** 厂商 state/status 串中的关键字（已 toUpperCase 后 contains） */
    public static final String VENDOR_TOKEN_SUCC = "SUCC";
    public static final String VENDOR_TOKEN_COMPLETE = "COMPLETE";
    public static final String VENDOR_TOKEN_DONE = "DONE";
    public static final String VENDOR_TOKEN_FAIL = "FAIL";
    public static final String VENDOR_TOKEN_ERROR = "ERROR";
    public static final String VENDOR_TOKEN_CANCEL = "CANCEL";
    public static final String VENDOR_TOKEN_REJECT = "REJECT";

    // --- JSON 请求体字段 ---

    public static final String JSON_MODEL = "model";
    public static final String JSON_PROMPT = "prompt";
    public static final String JSON_IMAGES = "images";
    public static final String JSON_ASPECT_RATIO = "aspect_ratio";
    public static final String JSON_DURATION = "duration";
    public static final String JSON_SUBJECTS = "subjects";
    public static final String JSON_VIDEOS = "videos";
    public static final String JSON_START_IMAGE = "start_image";
    public static final String JSON_IMAGE_SETTINGS = "image_settings";
    /** multiframe 单条 setting 内字段 */
    public static final String JSON_KEY_IMAGE = "key_image";
    /** subjects 单条主体的 name 字段（主体id，prompt 内以 @主体名 引用） */
    public static final String JSON_SUBJECT_NAME = "name";
    /** 是否使用智能主体库能力（参考生主体调用） */
    public static final String JSON_AUTO_SUBJECTS = "auto_subjects";

    /**
     * options → 上游请求体的官方字段白名单（各视频端点请求字段并集）。
     * Vidu 对多余字段返回 FieldUnwanted 拒单，业务侧 options 还承载 referenceImages /
     * generate_audio / 扇入上下文等内部键，必须白名单过滤后才允许并入请求体。
     * 音画字段（audio/bgm/audio_type/voice_id）与 callback_url 不在此列：走门禁注入，禁止 options 旁路。
     */
    public static final Set<String> UPSTREAM_FIELD_WHITELIST = Set.of(
            "model", "prompt", "images", "videos", "subjects", "auto_subjects",
            "duration", "seed", "aspect_ratio", "resolution", "movement_amplitude",
            "style", "is_rec", "payload", "off_peak",
            "watermark", "wm_position", "wm_url", "meta_data",
            "start_image", "image_settings",
            "template", "area", "beast", "story",
            "video_url", "video_creation_id", "audio_url", "text", "speed", "volume", "ref_photo_url");

    // --- options：合并/路由用键名 ---

    public static final String OPTIONS_KEY_IMAGES = "images";
    public static final String OPTIONS_VIDU_SCENARIO = "viduScenario";
    public static final String OPTIONS_VIDU_ENDPOINT = "viduEndpoint";
    public static final String OPTIONS_SCENARIO = "scenario";
    public static final String OPTIONS_IMAGE_SETTINGS = "image_settings";
    public static final String OPTIONS_START_IMAGE = "start_image";
    public static final String OPTIONS_SUBJECTS = "subjects";
    public static final String OPTIONS_VIDEOS = "videos";
    public static final String OPTIONS_START_END = "start_end";
    public static final String OPTIONS_START_END_MODE = "start_end_mode";
    public static final String OPTIONS_END_IMAGE_URL_CAMEL = "endImageUrl";
    public static final String OPTIONS_END_IMAGE_URL_SNAKE = "end_image_url";
    public static final String OPTIONS_KEY_IMAGES_ALT = "key_images";
    public static final String OPTIONS_KEY_IMAGES_CAMEL = "keyImages";

    // --- ProviderResponseHelper 常用路径（提交/查询）---

    public static final String JSON_TASK_ID = "task_id";
    public static final String JSON_PATH_DATA_TASK_ID = "data.task_id";
    public static final String JSON_PATH_DATA_ID = "data.id";
    public static final String JSON_ID = "id";
    public static final String JSON_PATH_OUTPUT_TASK_ID = "output.task_id";

    public static final String JSON_PATH_DATA_CREATIONS_0_URL = "data.creations.0.url";
    public static final String JSON_PATH_CREATIONS_0_URL = "creations.0.url";
    public static final String JSON_IMAGE_URL = "image_url";
    public static final String JSON_PATH_DATA_IMAGE_URL = "data.image_url";
    public static final String JSON_URL = "url";
    public static final String JSON_PATH_DATA_URL = "data.url";
    public static final String JSON_PATH_OUTPUT_RESULTS_0_URL = "output.results.0.url";

    public static final String JSON_STATE = "state";
    public static final String JSON_STATUS = "status";
    public static final String JSON_PATH_DATA_STATE = "data.state";
    public static final String JSON_PATH_DATA_STATUS = "data.status";
    public static final String JSON_TASK_STATUS = "task_status";
    public static final String JSON_PATH_DATA_TASK_STATUS = "data.task_status";
    public static final String JSON_PATH_OUTPUT_TASK_STATUS = "output.task_status";

    public static final String JSON_VIDEO_URL = "video_url";
    public static final String JSON_PATH_DATA_VIDEO_URL = "data.video_url";
    public static final String JSON_PATH_OUTPUT_VIDEO_URL = "output.video_url";

    public static final String JSON_PATH_ERROR_MESSAGE = "error.message";
    public static final String JSON_PATH_ERROR_MSG = "error.msg";
    public static final String JSON_ERROR = "error";
    public static final String JSON_MESSAGE = "message";
    public static final String JSON_PATH_DATA_ERROR = "data.error";
    public static final String JSON_PATH_DATA_MESSAGE = "data.message";

    /** size 形如 1024*1024 时的分隔 */
    public static final String SIZE_DIMENSION_SPLIT_REGEX = "\\*";

    // --- 端点路径常量（仅供前端展示/DB 种子/场景推断参考，运行时一律用 base_url + api_suffix 拼接，不在代码硬拼）---

    public static final String PATH_TEXT2VIDEO = "/ent/v2/text2video";
    public static final String PATH_IMG2VIDEO = "/ent/v2/img2video";
    public static final String PATH_START_END2VIDEO = "/ent/v2/start-end2video";
    public static final String PATH_REFERENCE2VIDEO = "/ent/v2/reference2video";
    public static final String PATH_MULTIFRAME = "/ent/v2/multiframe";
    public static final String PATH_REFERENCE2IMAGE = "/ent/v2/reference2image";
    /** 查询生成物端点（含任务ID占位符 %s）：用于 task_query_suffix 缺省参考 */
    public static final String PATH_TASK_CREATIONS = "/ent/v2/tasks/%s/creations";

    // --- 音画/通用请求体字段 ---

    /** 是否使用音视频直出能力 */
    public static final String JSON_AUDIO = "audio";
    /** 是否添加背景音乐 */
    public static final String JSON_BGM = "bgm";
    /** 音频类型：all/speech_only/sound_effect_only */
    public static final String JSON_AUDIO_TYPE = "audio_type";
    /** 音色ID */
    public static final String JSON_VOICE_ID = "voice_id";
    /** 分辨率参数：540p/720p/1080p/2K/4K */
    public static final String JSON_RESOLUTION = "resolution";
    /** 随机种子 */
    public static final String JSON_SEED = "seed";
    /** 运动幅度：auto/small/medium/large */
    public static final String JSON_MOVEMENT_AMPLITUDE = "movement_amplitude";
    /** 风格：general/anime */
    public static final String JSON_STYLE = "style";
    /** 回调地址 */
    public static final String JSON_CALLBACK_URL = "callback_url";

    // --- 对口型(lip-sync)请求体字段 ---

    /** 音频文件 URL（音频驱动对口型） */
    public static final String JSON_AUDIO_URL = "audio_url";
    /** 文本内容（文本驱动对口型） */
    public static final String JSON_TEXT = "text";
    /** 语速（仅文本驱动生效） */
    public static final String JSON_SPEED = "speed";
    /** 人脸参考图 URL（多人脸时指定目标） */
    public static final String JSON_REF_PHOTO_URL = "ref_photo_url";
    /** 音量（仅文本驱动生效） */
    public static final String JSON_VOLUME = "volume";

    // --- audio_type 枚举值 ---

    /** 音效+人声 */
    public static final String AUDIO_TYPE_ALL = "all";
    /** 仅人声 */
    public static final String AUDIO_TYPE_SPEECH_ONLY = "speech_only";
    /** 仅音效 */
    public static final String AUDIO_TYPE_SOUND_EFFECT_ONLY = "sound_effect_only";

    /** audio_type 合法枚举集合（请求侧本地兜底校验，capability.audioTypes 为空时使用） */
    public static final Set<String> AUDIO_TYPE_ENUMS = Set.of(
            AUDIO_TYPE_ALL, AUDIO_TYPE_SPEECH_ONLY, AUDIO_TYPE_SOUND_EFFECT_ONLY);

    // --- 查询响应错误码字段 ---

    /** 查询生成物接口错误码字段 */
    public static final String JSON_ERR_CODE = "err_code";
    public static final String JSON_PATH_DATA_ERR_CODE = "data.err_code";

    // --- 错误码分类（用于 query 失败判定，区分可重试与终态）---

    /** 可重试错误码：命中时保持 PROCESSING 让轮询继续兜底，不判 FAILED */
    public static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            "QuotaExceeded",
            "TooManyRequests",
            "SystemThrottling",
            "OperationInProcess",
            "InternalServiceFailure");

    /** 终态失败错误码：命中即直接判 FAILED（触发失败收口/退款），轮询不再继续 */
    public static final Set<String> TERMINAL_ERROR_CODES = Set.of(
            "CreditInsufficient",
            "TaskPromptPolicyViolation",
            "CreationPolicyViolation",
            "AuditSubmitIllegal",
            "Unauthorized",
            "Forbidden",
            "ModelUnavailable");

    // --- 回调 HMAC 验签常量（参考 vidu 回调签名算法）---

    /** 回调固定 access_key，参与签名串与 X-HMAC-ACCESS-KEY 头校验 */
    public static final String CALLBACK_ACCESS_KEY = "vidu";
    /** 签名头：列出参与签名的请求头字段（分号分隔） */
    public static final String HDR_HMAC_SIGNED_HEADERS = "X-HMAC-SIGNED-HEADERS";
    /** 签名头：最终计算出的签名值（base64） */
    public static final String HDR_HMAC_SIGNATURE = "X-HMAC-SIGNATURE";
    /** 签名头：算法标识，固定 hmac-sha256 */
    public static final String HDR_HMAC_ALGORITHM = "hmac-sha256";
    /** 签名头：访问密钥标识，固定 vidu */
    public static final String HDR_HMAC_ACCESS_KEY = "X-HMAC-ACCESS-KEY";
    /** 签名头：随机串，用于防重放 */
    public static final String HDR_REQUEST_NONCE = "x-request-nonce";

    // --- options：音画/分辨率等键名（用于读取请求 options 透传值）---

    public static final String OPTIONS_RESOLUTION = "resolution";
    public static final String OPTIONS_SEED = "seed";
    public static final String OPTIONS_MOVEMENT_AMPLITUDE = "movement_amplitude";

    // --- options：对口型(lip-sync)字段键名（用于从 options 读取覆盖值）---

    /** 源视频 URL（缺省回退 request.imageUrl） */
    public static final String OPTIONS_VIDEO_URL = "video_url";
    /** 音频驱动 URL */
    public static final String OPTIONS_AUDIO_URL = "audio_url";
    /** 文本驱动语速 */
    public static final String OPTIONS_SPEED = "speed";
    /** 人脸参考图 URL */
    public static final String OPTIONS_REF_PHOTO_URL = "ref_photo_url";
    /** 文本驱动音量 */
    public static final String OPTIONS_VOLUME = "volume";
    /** 对口型音色ID（缺省回退 request.voiceId） */
    public static final String OPTIONS_VOICE_ID = "voice_id";

    // --- 回调地址配置（供应商/模型 schedule_strategy_json）---

    /** 回调基地址在 schedule_strategy_json 中的键名：非空则提交任务时注入 callback_url 开启回调加速，留空则纯轮询（模型级优先，缺省回退供应商级） */
    public static final String STRATEGY_KEY_CALLBACK_BASE_URL = "callbackBaseUrl";
}
