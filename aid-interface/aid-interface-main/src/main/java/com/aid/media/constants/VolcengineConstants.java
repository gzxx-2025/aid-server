package com.aid.media.constants;

/**
 * 火山引擎方舟协议、默认模型、请求字段与任务状态常量。
 */
public final class VolcengineConstants {

    private VolcengineConstants() {
    }

    // --- 协议（与库表 aid_media_task.protocol、路由一致）---

    /** Seedream 图片同步生成协议 */
    public static final String PROTOCOL_SEEDREAM_IMAGE = "seedream-image";
    /** Seedance 视频异步生成协议 */
    public static final String PROTOCOL_SEEDANCE_VIDEO = "seedance-video";

    /** aid_ai_provider.provider_code 约定：火山方舟（Seedream/Seedance）服务商登记为该 code，调度层按此精确路由 */
    public static final String PROVIDER_CODE = "volcengine";

    /** HTTP 单次请求超时 */
    public static final int HTTP_TIMEOUT_SECONDS = 120;

    // --- 默认模型 ID ---

    public static final String DEFAULT_IMAGE_MODEL = "doubao-seedream-5-0-pro-260628";
    public static final String DEFAULT_VIDEO_MODEL = "doubao-seedance-2-0-260128";
    /** 视频默认时长（秒） */
    public static final long DEFAULT_VIDEO_DURATION_SECONDS = 5L;

    // --- 归一化任务状态（与 MediaTaskStatus 字符串一致）---

    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";

    /** 厂商返回 status 小写（Seedance 任务） */
    public static final String VENDOR_STATUS_SUCCEEDED = "succeeded";
    public static final String VENDOR_STATUS_FAILED = "failed";

    // --- 日志 ---

    public static final int LOG_PROMPT_ABBREV_MAX = 80;

    // --- Seedream 图片：GenerateImages 默认与 options 键 ---

    public static final String DEFAULT_IMAGE_SIZE = "2K";
    public static final String DEFAULT_OUTPUT_FORMAT = "png";
    public static final boolean DEFAULT_STREAM = false;
    public static final boolean DEFAULT_WATERMARK = false;

    public static final String JSON_IMAGES = "images";
    public static final String JSON_SEQUENTIAL_IMAGE_GENERATION = "sequential_image_generation";
    public static final String JSON_SEQUENTIAL_IMAGE_GENERATION_OPTIONS = "sequential_image_generation_options";
    public static final String JSON_MAX_IMAGES = "max_images";
    public static final String JSON_SIZE = "size";
    public static final String JSON_WATERMARK = "watermark";
    public static final String JSON_OUTPUT_FORMAT = "output_format";
    public static final String JSON_SEED = "seed";
    public static final String JSON_GUIDANCE_SCALE = "guidance_scale";

    // --- Seedance 视频：content 项 type / role ---

    public static final String CONTENT_TYPE_TEXT = "text";
    public static final String CONTENT_TYPE_IMAGE_URL = "image_url";
    public static final String CONTENT_TYPE_AUDIO_URL = "audio_url";
    public static final String CONTENT_TYPE_VIDEO_URL = "video_url";
    public static final String ROLE_FIRST_FRAME = "first_frame";
    public static final String ROLE_LAST_FRAME = "last_frame";
    // Seedance 2.0 多模态参考图 role：官方文档为 reference_image，锁定参考图角色样貌
    public static final String ROLE_REFERENCE = "reference_image";
    public static final String ROLE_REFERENCE_AUDIO = "reference_audio";
    public static final String ROLE_REFERENCE_VIDEO = "reference_video";

    // --- Seedance：options 键 ---

    public static final String OPTIONS_LAST_FRAME_IMAGE_URL = "lastFrameImageUrl";
    public static final String OPTIONS_REFERENCE_IMAGES = "referenceImages";
    public static final String OPTIONS_REFERENCE_VIDEOS = "referenceVideos";
    public static final String OPTIONS_REFERENCE_VIDEO_URL = "referenceVideoUrl";
    public static final String OPTIONS_GENERATE_AUDIO = "generate_audio";
    public static final String OPTIONS_RESOLUTION = "resolution";
    public static final String OPTIONS_RETURN_LAST_FRAME = "return_last_frame";
    public static final String OPTIONS_CAMERA_FIXED = "camera_fixed";
    public static final String OPTIONS_CALLBACK_URL = "callback_url";
    public static final String OPTIONS_OMNI_REFERENCE_TASK_TYPE = "omni_reference_task_type";
}
