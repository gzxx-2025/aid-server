package com.aid.media.constants;

/**
 * DashScope（阿里云百炼）图片和视频生成相关常量：路径、Header、模型名前缀、JSON 字段、错误码等。
 */
public final class DashscopeConstants {

    private DashscopeConstants() {
    }

    /** 协议标识，与库表 aid_media_task.protocol、各 Dashscope*ProviderClient.protocol() 一致 */
    public static final String PROTOCOL_IMAGE = "dashscope-image";
    public static final String PROTOCOL_VIDEO = "dashscope-video";

    /** aid_ai_provider.provider_code 约定：阿里百炼服务商登记为该 code，调度层按此精确路由 */
    public static final String PROVIDER_CODE = "dashscope";

    /** 任务 ID 占位符（部分网关配置模板使用） */
    public static final String URL_TASK_ID_PLACEHOLDER = "{taskId}";

    /** HTTP 客户端超时（毫秒） */
    public static final int HTTP_TIMEOUT_MS = 120_000;

    /** DashScope 异步调用必须携带的请求头 */
    public static final String HEADER_ASYNC = "X-DashScope-Async";
    public static final String HEADER_ASYNC_ENABLE = "enable";

    /** 通用 HTTP Header */
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";
    public static final String CONTENT_TYPE_JSON = "application/json";

    /** 千问模型名前缀（用于双模判断和 supports 方法） */
    public static final String MODEL_QWEN_IMAGE = "qwen-image";
    public static final String MODEL_QWEN_IMAGE_PLUS_PREFIX = "qwen-image-plus";
    public static final String MODEL_QWEN_IMAGE_MAX_PREFIX = "qwen-image-max";
    public static final String MODEL_QWEN_IMAGE_2_0_PREFIX = "qwen-image-2.0";
    public static final String MODEL_QWEN_IMAGE_2 = "qwen-image-2";

    /** 万相 / 可灵等模型名前缀 */
    public static final String MODEL_WAN_26_PREFIX = "wan2.6";
    /** 万相 2.7 模型名前缀：使用与 2.6 相同的新版 image-generation 协议 */
    public static final String MODEL_WAN_27_PREFIX = "wan2.7";
    /** 万相 2.6 图像编辑模型前缀（wan2.6-image，编辑模式必须 1~4 张输入图） */
    public static final String MODEL_WAN_26_IMAGE_PREFIX = "wan2.6-image";
    /** 万相 2.7 Pro 模型前缀（仅该模型支持 4K，且 4K 仅限纯文生图场景） */
    public static final String MODEL_WAN_27_PRO_PREFIX = "wan2.7-image-pro";
    public static final String MODEL_WAN_2_DOT_PREFIX = "wan2.";
    public static final String MODEL_WANX_2_PREFIX = "wanx2.";
    /** wanx2.1-imageedit 图像编辑模型精确名称（独立协议，不同于文生图） */
    public static final String MODEL_WANX_IMAGE_EDIT = "wanx2.1-imageedit";
    public static final String MODEL_KLING_PREFIX = "kling/";
    /** 爱诗视频模型名前缀 */
    public static final String MODEL_PIXVERSE_PREFIX = "pixverse/";
    /** 旧版首尾帧 wanx 模型名前缀 */
    public static final String MODEL_LEGACY_WANX_KF2V_PREFIX = "wanx2.1-kf2v";
    /** 万相视频族（wan* / wanx*，注意 wanx 亦以 wan 开头） */
    public static final String MODEL_VIDEO_WAN_PREFIX = "wan";
    public static final String MODEL_VIDEO_WANX_PREFIX = "wanx";

    /** 归一化后的任务状态（与 MediaTaskStatus 枚举值字符串一致） */
    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    /** 厂商原始状态串中的关键字（大写比较） */
    public static final String VENDOR_TOKEN_SUCC = "SUCC";
    public static final String VENDOR_STATUS_SUCCESS = "SUCCESS";
    public static final String VENDOR_STATUS_COMPLETED = "COMPLETED";
    public static final String VENDOR_TOKEN_COMPLETE = "COMPLETE";
    public static final String VENDOR_TOKEN_CANCEL = "CANCEL";
    public static final String VENDOR_TOKEN_UNKNOWN = "UNKNOWN";
    public static final String VENDOR_TOKEN_FAIL = "FAIL";
    public static final String VENDOR_TOKEN_ERROR = "ERROR";

    /** 错误码与错误消息（用于同步失败后是否回退异步的判断） */
    public static final String ERROR_INVALID_PARAMETER = "InvalidParameter";
    public static final String ERROR_INVALID_PARAMETER_PREFIX = "InvalidParameter.";
    public static final String ERROR_URL_ERROR_SUBSTRING = "url error";

    /** JSON 请求字段名（避免魔法字符串） */
    public static final String JSON_MODEL = "model";
    public static final String JSON_INPUT = "input";
    public static final String JSON_PROMPT = "prompt";
    public static final String JSON_MESSAGES = "messages";
    public static final String JSON_ROLE = "role";
    public static final String JSON_ROLE_USER = "user";
    public static final String JSON_CONTENT = "content";
    public static final String JSON_TEXT = "text";
    /** wan2.6/wan2.7 messages.content 中参考图项的字段名（与官方文档对齐） */
    public static final String JSON_IMAGE = "image";
    public static final String JSON_PARAMETERS = "parameters";
    public static final String JSON_NEGATIVE_PROMPT = "negative_prompt";
    public static final String JSON_N = "n";
    public static final String JSON_PROMPT_EXTEND = "prompt_extend";
    /** wan2.7 思考模式：官方默认 true，业务固定关闭以降低耗时 */
    public static final String JSON_THINKING_MODE = "thinking_mode";
    /** wan2.7 组图模式：官方默认 false */
    public static final String JSON_ENABLE_SEQUENTIAL = "enable_sequential";
    /** wan2.6-image 图像编辑模式开关：false=编辑（1~4 张参考图），true=图文混排 */
    public static final String JSON_ENABLE_INTERLEAVE = "enable_interleave";
    /** wan2.6-image 编辑模式 n 上限（官方 1~4，默认 4；平台按 expectedImageCount 显式下发防止按默认 4 张生成） */
    public static final int WAN_NEW_MAX_OUTPUT_N = 4;
    public static final String JSON_WATERMARK = "watermark";
    public static final String JSON_SIZE = "size";
    /** 设定卡 21:9 横版构图显式像素尺寸：万相编辑模式需显式尺寸才能稳定 21:9 构图并利于 SKU 计费命中 */
    public static final String CARD_IMAGE_SIZE_2K_21_9 = "2016*864";
    /** 白底形态图 1:1 显式像素尺寸：万相参考图编辑模式仅传档位不会强制 1:1，需显式像素 */
    public static final String FORM_IMAGE_SIZE_2K_1_1 = "2048*2048";
    public static final String FORM_IMAGE_SIZE_1K_1_1 = "1024*1024";
    /** 视频提交体常用字段 */
    public static final String JSON_FIRST_FRAME_URL = "first_frame_url";
    /** 首尾帧生视频：尾帧字段（wan2.2-kf2v-flash / wanx2.1-kf2v-plus 协议） */
    public static final String JSON_LAST_FRAME_URL = "last_frame_url";
    /** 首尾帧模型通用命中关键字（wan2.2-kf2v-flash、wanx2.1-kf2v-plus 等均含 kf2v） */
    public static final String MODEL_KF2V_KEYWORD = "kf2v";
    /** kf2v 首尾帧模型官方固定时长（文档：duration 固定为 5 且不支持修改） */
    public static final int KF2V_FIXED_DURATION_SECONDS = 5;
    public static final String JSON_MEDIA = "media";
    public static final String JSON_IMG_URL = "img_url";
    public static final String JSON_DURATION = "duration";
    public static final String JSON_RESOLUTION = "resolution";
    public static final String JSON_TYPE = "type";
    public static final String JSON_URL = "url";
    public static final String MEDIA_ITEM_TYPE_IMAGE_URL = "image_url";
    public static final String DEFAULT_VIDEO_RESOLUTION = "720P";
    /** HappyHorse 模型名前缀（参考生视频，多图参考融合） */
    public static final String MODEL_HAPPYHORSE_PREFIX = "happyhorse";
    /** media 元素类型：参考图（与 HappyHorse 文档一致，区别于 pixverse/kling 的 image_url） */
    public static final String MEDIA_ITEM_TYPE_REFERENCE_IMAGE = "reference_image";
    /** parameters.ratio：HappyHorse 用 ratio 表示宽高比（而非 size） */
    public static final String JSON_RATIO = "ratio";
    /** HappyHorse 参考图硬上限（文档：1~9 张） */
    public static final int HAPPYHORSE_MAX_REFERENCE_IMAGES = 9;
    /** HappyHorse 默认宽高比 */
    public static final String HAPPYHORSE_DEFAULT_RATIO = "16:9";
    /** HappyHorse 时长范围（秒）与默认值（文档：3~15，默认 5） */
    public static final int HAPPYHORSE_DURATION_MIN = 3;
    public static final int HAPPYHORSE_DURATION_MAX = 15;
    public static final int HAPPYHORSE_DEFAULT_DURATION = 5;
    /** HappyHorse 高清档（文档分辨率仅 720P / 1080P） */
    public static final String RESOLUTION_1080P = "1080P";
    /** HappyHorse 合法宽高比集合（文档枚举） */
    public static final java.util.Set<String> HAPPYHORSE_RATIOS = java.util.Set.of(
            "16:9", "9:16", "3:4", "4:3", "4:5", "5:4", "1:1", "9:21", "21:9");
    public static final String JSON_KEY_CODE = "code";
    public static final String JSON_KEY_MESSAGE = "message";
    public static final String JSON_PATH_OUTPUT_CODE = "output.code";
    public static final String JSON_PATH_OUTPUT_MESSAGE = "output.message";

    /** options 顶层 key，与 mergeOptions 逻辑对应 */
    public static final String OPTIONS_KEY_PARAMETERS = "parameters";
    public static final String OPTIONS_KEY_INPUT = "input";

    /** 默认值 */
    public static final String DEFAULT_IMAGE_SIZE = "1024*1024";
    public static final String DEFAULT_NEGATIVE_PROMPT = " ";
    public static final int DEFAULT_N = 1;
    public static final boolean DEFAULT_PROMPT_EXTEND = true;
    public static final boolean DEFAULT_WATERMARK = false;

    /** 日志与响应处理 */
    public static final int LOG_RESPONSE_SNIPPET_MAX = 512;
}
