package com.aid.media.constants;

import java.util.Map;

/**
 * 即梦（火山视觉）图片 / 视频 API 的协议、路由、req_key 映射与常用字段常量。
 *
 * @author 视觉AID
 */
public final class JimengConstants {

    private JimengConstants() {
    }

    // --- 协议与路由（与任务表 protocol 一致） ---

    /** Provider 协议标识，MediaGenerationServiceImpl 路由与任务表 protocol 都用这个值 */
    public static final String PROTOCOL_IMAGE = "jimeng-image";

    /** 视频 Provider 协议标识，与任务表 protocol、JimengVideoProviderClient.protocol() 一致 */
    public static final String PROTOCOL_VIDEO = "jimeng-video";

    /** aid_ai_provider.provider_code 约定：运维在数据库里把即梦服务商登记为该 code */
    public static final String PROVIDER_CODE = "jimeng";

    // --- 四个模型的外部 model_code ---

    public static final String MODEL_CODE_V31 = "jimeng-image-3.1";
    public static final String MODEL_CODE_V40 = "jimeng-image-4.0";
    public static final String MODEL_CODE_V46 = "jimeng-image-4.6";
    public static final String MODEL_CODE_ULTRA = "jimeng-image-ultra";

    // --- 对应的上游 req_key（官方文档固定值） ---

    public static final String REQ_KEY_V31 = "jimeng_t2i_v31";
    public static final String REQ_KEY_V40 = "jimeng_t2i_v40";
    public static final String REQ_KEY_V46 = "jimeng_seedream46_cvtob";
    public static final String REQ_KEY_ULTRA = "jimeng_i2i_seed3_tilesr_cvtob";

    /**
     * 内部 modelCode → 上游 req_key 固定映射。
     * 注意：查询任务和提交任务必须使用同一个 req_key。
     */
    public static final Map<String, String> MODEL_CODE_TO_REQ_KEY = Map.of(
            MODEL_CODE_V31, REQ_KEY_V31,
            MODEL_CODE_V40, REQ_KEY_V40,
            MODEL_CODE_V46, REQ_KEY_V46,
            MODEL_CODE_ULTRA, REQ_KEY_ULTRA
    );

    // --- 视频模型的外部 model_code 与上游 req_key ---

    /** 即梦视频 3.0 Pro 外部 model_code */
    public static final String VIDEO_MODEL_CODE_V30_PRO = "jimeng-video-3.0-pro";
    /** 即梦视频 3.0 外部 model_code */
    public static final String VIDEO_MODEL_CODE_V30 = "jimeng-video-3.0";

    /** 即梦视频 3.0 Pro 上游 req_key（官方固定值，文生/图生共用一个服务标识） */
    public static final String VIDEO_REQ_KEY_V30_PRO = "jimeng_ti2v_v30_pro";

    // 即梦视频 3.0（非 Pro）官方按「场景 × 分辨率」拆分 6 个 req_key，没有统一的 ti2v 标识：
    /** 3.0 720P 文生视频 */
    public static final String VIDEO_REQ_KEY_V30_T2V_720 = "jimeng_t2v_v30";
    /** 3.0 720P 图生视频-首帧 */
    public static final String VIDEO_REQ_KEY_V30_I2V_FIRST_720 = "jimeng_i2v_first_v30";
    /** 3.0 720P 图生视频-首尾帧 */
    public static final String VIDEO_REQ_KEY_V30_I2V_FIRST_TAIL_720 = "jimeng_i2v_first_tail_v30";
    /** 3.0 1080P 文生视频 */
    public static final String VIDEO_REQ_KEY_V30_T2V_1080 = "jimeng_t2v_v30_1080p";
    /** 3.0 1080P 图生视频-首帧 */
    public static final String VIDEO_REQ_KEY_V30_I2V_FIRST_1080 = "jimeng_i2v_first_v30_1080";
    /** 3.0 1080P 图生视频-首尾帧 */
    public static final String VIDEO_REQ_KEY_V30_I2V_FIRST_TAIL_1080 = "jimeng_i2v_first_tail_v30_1080";

    /**
     * 查询兜底映射（仅在 providerTaskId 未携带 req_key 的历史任务上使用）：
     * 提交时的真实 req_key 已编码进 providerTaskId（taskId|reqKey），查询优先解码使用。
     */
    public static final Map<String, String> VIDEO_MODEL_CODE_TO_REQ_KEY = Map.of(
            VIDEO_MODEL_CODE_V30_PRO, VIDEO_REQ_KEY_V30_PRO,
            VIDEO_MODEL_CODE_V30, VIDEO_REQ_KEY_V30_T2V_720
    );

    /** providerTaskId 复合分隔符：taskId|reqKey（3.0 系 req_key 随场景/分辨率变化，查询必须回用提交时的值） */
    public static final String VIDEO_TASK_ID_REQ_KEY_SEPARATOR = "|";

    /** 视频 1080P 档位标识（req_key 选择用） */
    public static final String VIDEO_RESOLUTION_1080P = "1080P";
    /** 视频 720P 档位标识（req_key 选择用，3.0 默认档，与计费缺省推断一致） */
    public static final String VIDEO_RESOLUTION_720P = "720P";

    // --- 视频请求/响应字段（与图片共用 SigV4 + Action 链路）---

    /** 帧数字段：frames = 24 * 秒数 + 1（5s→121，10s→241） */
    public static final String JSON_FRAMES = "frames";
    /** 视频比例字段（仅文生视频生效） */
    public static final String JSON_ASPECT_RATIO = "aspect_ratio";
    /** 响应：生成的视频 URL（有效期 1 小时） */
    public static final String RESP_VIDEO_URL = "video_url";

    /** 每秒帧数基数：frames = FRAMES_PER_SECOND_FACTOR * 秒数 + 1 */
    public static final int VIDEO_FRAMES_PER_SECOND_FACTOR = 24;
    /** 官方仅支持 5s / 10s 两档（frames 可选取值 [121, 241]），其他秒数必须收口 */
    public static final int VIDEO_DURATION_SHORT_SECONDS = 5;
    public static final int VIDEO_DURATION_LONG_SECONDS = 10;
    /** 秒数收口分界：<=7 归 5s，>7 归 10s（就近取档） */
    public static final int VIDEO_DURATION_SNAP_THRESHOLD_SECONDS = 7;
    /** 默认时长（秒）：上游默认 121 帧 = 5s */
    public static final int VIDEO_DEFAULT_DURATION_SECONDS = 5;
    /** 默认比例（文生视频） */
    public static final String VIDEO_DEFAULT_ASPECT_RATIO = "16:9";
    /** prompt 上限（视频文档：不超过 800 字符） */
    public static final int VIDEO_PROMPT_MAX_LENGTH = 800;

    // --- 参考图数量上限（与官方文档一致，capability_json.maxReferenceImages 可覆盖） ---

    /** 3.1：文生图，无参考图 */
    public static final int MAX_REF_IMAGES_V31 = 0;
    /** 4.0：官方支持输入 0~10 张图 */
    public static final int MAX_REF_IMAGES_V40 = 10;
    /** 4.6：官方支持输入 0~14 张图 */
    public static final int MAX_REF_IMAGES_V46 = 14;
    /** ultra：必须且只能 1 张参考图 */
    public static final int MIN_REF_IMAGES_ULTRA = 1;
    public static final int MAX_REF_IMAGES_ULTRA = 1;

    // --- HTTP / 鉴权 ---

    /** 即梦统一网关，所有 req_key 都走此主机 */
    public static final String DEFAULT_HOST = "visual.volcengineapi.com";
    /** 默认协议与 base_url（aid_ai_provider.base_url 未配置时兜底） */
    public static final String DEFAULT_BASE_URL = "https://visual.volcengineapi.com";
    /** 火山视觉服务固定 region */
    public static final String REGION = "cn-north-1";
    /** 火山视觉服务固定 service name（SigV4 scope 使用） */
    public static final String SERVICE = "cv";
    /** SigV4 算法标识 */
    public static final String SIGN_ALGORITHM = "HMAC-SHA256";
    /** SigV4 scope 结尾 */
    public static final String SIGN_REQUEST_SUFFIX = "request";
    /** HTTP 超时（毫秒） */
    public static final int HTTP_TIMEOUT_MS = 120_000;

    // --- Query 参数 ---

    public static final String QUERY_ACTION = "Action";
    public static final String QUERY_VERSION = "Version";
    /** 查询参数版本号（官方固定 2022-08-31） */
    public static final String API_VERSION = "2022-08-31";
    /** 提交任务 Action */
    public static final String ACTION_SUBMIT = "CVSync2AsyncSubmitTask";
    /** 查询任务 Action */
    public static final String ACTION_QUERY = "CVSync2AsyncGetResult";

    // --- 请求头 ---

    public static final String HEADER_HOST = "Host";
    public static final String HEADER_X_DATE = "X-Date";
    public static final String HEADER_X_CONTENT_SHA256 = "X-Content-Sha256";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_TYPE_JSON = "application/json";

    // --- 请求体字段 ---

    public static final String JSON_REQ_KEY = "req_key";
    public static final String JSON_PROMPT = "prompt";
    public static final String JSON_IMAGE_URLS = "image_urls";
    /** 官方入参：图片 base64 数组（与 image_urls 二选一，启用 Base64 传图时使用） */
    public static final String JSON_BINARY_DATA_BASE64 = "binary_data_base64";
    public static final String JSON_TASK_ID = "task_id";
    public static final String JSON_REQ_JSON = "req_json";
    public static final String JSON_WIDTH = "width";
    public static final String JSON_HEIGHT = "height";
    public static final String JSON_SIZE = "size";
    public static final String JSON_SCALE = "scale";
    public static final String JSON_FORCE_SINGLE = "force_single";
    public static final String JSON_MIN_RATIO = "min_ratio";
    public static final String JSON_MAX_RATIO = "max_ratio";
    public static final String JSON_USE_PRE_LLM = "use_pre_llm";
    public static final String JSON_SEED = "seed";
    public static final String JSON_RESOLUTION = "resolution";
    public static final String JSON_RETURN_URL = "return_url";
    /** 查询时默认请求返回链接形式（24h 有效） */
    public static final String DEFAULT_RETURN_URL_JSON = "{\"return_url\":true}";

    // --- 响应字段 ---

    public static final String RESP_CODE = "code";
    public static final String RESP_DATA = "data";
    public static final String RESP_MESSAGE = "message";
    public static final String RESP_TASK_ID = "task_id";
    public static final String RESP_STATUS = "status";
    public static final String RESP_IMAGE_URLS = "image_urls";
    /** 成功 code，官方固定 10000 */
    public static final int RESP_CODE_SUCCESS = 10000;
    /** request_id 字段（排查上游错误的关键信息） */
    public static final String RESP_REQUEST_ID = "request_id";

    // --- 任务状态（官方字符串） ---

    public static final String VENDOR_STATUS_IN_QUEUE = "in_queue";
    public static final String VENDOR_STATUS_GENERATING = "generating";
    public static final String VENDOR_STATUS_DONE = "done";
    public static final String VENDOR_STATUS_NOT_FOUND = "not_found";
    public static final String VENDOR_STATUS_EXPIRED = "expired";

    // --- 归一化任务状态（与平台轮询状态机一致） ---

    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    // --- options 透传键 ---

    public static final String OPTIONS_WIDTH = "width";
    public static final String OPTIONS_HEIGHT = "height";
    public static final String OPTIONS_SCALE = "scale";
    public static final String OPTIONS_FORCE_SINGLE = "force_single";
    public static final String OPTIONS_MIN_RATIO = "min_ratio";
    public static final String OPTIONS_MAX_RATIO = "max_ratio";
    public static final String OPTIONS_USE_PRE_LLM = "use_pre_llm";
    public static final String OPTIONS_SEED = "seed";
    public static final String OPTIONS_RESOLUTION = "resolution";
    public static final String OPTIONS_IMAGES = "images";
    public static final String OPTIONS_REFERENCE_IMAGES = "referenceImages";

    /** size 形如 1024*1024 时的分隔 */
    public static final String SIZE_DIMENSION_SPLIT_REGEX = "\\*";

    // --- prompt 限制（4.0 / 4.6 文档均为 800 字符） ---

    /** prompt 最大长度：超长截断并 log.warn */
    public static final int PROMPT_MAX_LENGTH = 800;

    // --- size 面积常量（用于 "1K"/"2K"/"4K" 到面积 int 的翻译） ---

    /** 1K 面积：1024 * 1024 = 1048576 */
    public static final int SIZE_AREA_1K = 1024 * 1024;
    /** 2K 面积（默认值）：2048 * 2048 = 4194304 */
    public static final int SIZE_AREA_2K = 2048 * 2048;
    /** 4K 面积：4096 * 4096 = 16777216 */
    public static final int SIZE_AREA_4K = 4096 * 4096;

    // --- scale 差异（4.0 / 4.6 / ultra 类型与范围不同，需按模型分流） ---

    /** 4.0 scale：float [0, 1]，默认 0.5（文本影响程度） */
    public static final double SCALE_V40_MIN = 0.0;
    public static final double SCALE_V40_MAX = 1.0;
    public static final double SCALE_V40_DEFAULT = 0.5;
    /** 4.6 scale：int [1, 100]，默认 50（文本影响程度） */
    public static final int SCALE_V46_MIN = 1;
    public static final int SCALE_V46_MAX = 100;
    public static final int SCALE_V46_DEFAULT = 50;
    /** ultra scale：int [0, 100]，默认 50（细节生成程度） */
    public static final int SCALE_ULTRA_MIN = 0;
    public static final int SCALE_ULTRA_MAX = 100;

    // --- ultra resolution 官方可选值（必须小写下发） ---

    public static final String ULTRA_RESOLUTION_4K = "4k";
    public static final String ULTRA_RESOLUTION_8K = "8k";

    /** 日志中 prompt 截断长度，避免刷屏 */
    public static final int LOG_PROMPT_ABBREV_MAX = 200;
    public static final int LOG_RESPONSE_SNIPPET_MAX = 500;
}
