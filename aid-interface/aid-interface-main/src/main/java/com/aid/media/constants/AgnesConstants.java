package com.aid.media.constants;

import java.util.Set;

/**
 * Agnes AI（OpenAI 兼容网关）服务商常量：协议标识、provider_code、默认参数、JSON 字段等。
 *
 * @author 视觉AID
 */
public final class AgnesConstants {

    private AgnesConstants() {
    }
    /** aid_ai_provider.provider_code 约定：Agnes 服务商登记为该 code，调度层按此精确路由 */
    public static final String PROVIDER_CODE = "agnes";

    /** 图片协议标识：与 aid_ai_model.protocol、AgnesImageProviderClient.protocol() 一致 */
    public static final String PROTOCOL_IMAGE = "agnes-image";

    /** 视频协议标识：与 aid_ai_model.protocol、AgnesVideoProviderClient.protocol() 一致 */
    public static final String PROTOCOL_VIDEO = "agnes-video";
    /** 默认图片模型 */
    public static final String DEFAULT_IMAGE_MODEL = "agnes-image-2.1-flash";

    /** Agnes Image 2.0 Flash 上游模型名 */
    public static final String IMAGE_MODEL_20_FLASH = "agnes-image-2.0-flash";

    /** Agnes Image 2.1 Flash 上游模型名 */
    public static final String IMAGE_MODEL_21_FLASH = "agnes-image-2.1-flash";

    /** 默认视频模型 */
    public static final String DEFAULT_VIDEO_MODEL = "agnes-video-v2.0";
    /** 请求字段：模型名 */
    public static final String JSON_MODEL = "model";
    /** 请求字段：提示词 */
    public static final String JSON_PROMPT = "prompt";
    /** 请求字段：图片尺寸（档位 1K/2K/3K/4K 或精确 WxH，如 1024x768） */
    public static final String JSON_SIZE = "size";
    /** 请求字段：宽高比（与档位式 size 配合使用，如 16:9） */
    public static final String JSON_RATIO = "ratio";
    /** 请求字段：输入图片数组（图生图 / 多图合成，支持公网 URL 或 Data URI Base64） */
    public static final String JSON_IMAGE = "image";
    /** 请求字段：扩展参数对象（response_format / mode / image 等放此处） */
    public static final String JSON_EXTRA_BODY = "extra_body";
    /** extra_body 字段：输出格式（url / b64_json） */
    public static final String JSON_RESPONSE_FORMAT = "response_format";
    /** extra_body 字段：视频关键帧模式 */
    public static final String JSON_MODE = "mode";

    /** 视频关键帧（首尾帧）模式值：extra_body.image=[首帧,尾帧] + mode=keyframes，在关键帧间生成平滑过渡 */
    public static final String MODE_KEYFRAMES = "keyframes";
    /** 文生图顶层字段：要求 Base64 输出（图生图请用 extra_body.response_format=b64_json） */
    public static final String JSON_RETURN_BASE64 = "return_base64";

    /** 图片默认尺寸（WxH） */
    public static final String DEFAULT_IMAGE_SIZE = "1024x1024";

    /** 图片默认档位尺寸（配合 ratio 使用，1:1 时输出 1024x1024） */
    public static final String SIZE_TIER_1K = "1K";

    /** 图片档位式尺寸合法集合（官方推荐：档位 + ratio，输出尺寸可预期） */
    public static final Set<String> SIZE_TIERS = Set.of("1K", "2K", "3K", "4K");

    /** Agnes Image 2.0 文档明确列出的像素尺寸 */
    public static final Set<String> IMAGE_20_SIZES =
            Set.of("1024x768", "1024x1024", "768x1024");

    /** 图片支持的宽高比集合（与档位式 size 配合下发） */
    public static final Set<String> SUPPORTED_RATIOS =
            Set.of("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9");

    /** 输出格式：Base64（图片由 Provider 落 OSS，避免再次跨网下载上游 URL） */
    public static final String RESPONSE_FORMAT_B64 = "b64_json";

    /** 输出格式：URL（上游直出图片 URL，系统后续下载转存 OSS） */
    public static final String RESPONSE_FORMAT_URL = "url";
    /** 视频请求字段：宽 */
    public static final String JSON_WIDTH = "width";
    /** 视频请求字段：高 */
    public static final String JSON_HEIGHT = "height";
    /** 视频请求字段：总帧数（必须满足 8n+1，且 ≤ 441） */
    public static final String JSON_NUM_FRAMES = "num_frames";
    /** 视频请求字段：帧率（1-60） */
    public static final String JSON_FRAME_RATE = "frame_rate";
    /** 视频请求字段：负向提示词 */
    public static final String JSON_NEGATIVE_PROMPT = "negative_prompt";

    /** 视频默认宽（doc 默认 1152） */
    public static final int DEFAULT_VIDEO_WIDTH = 1152;
    /** 视频默认高（doc 默认 768） */
    public static final int DEFAULT_VIDEO_HEIGHT = 768;
    /** 视频默认帧率 */
    public static final int DEFAULT_FRAME_RATE = 24;
    /** 视频默认帧数（约 5 秒 @24fps，满足 8n+1） */
    public static final int DEFAULT_NUM_FRAMES = 121;
    /** 视频帧数上限（doc 约束 ≤ 441） */
    public static final int MAX_NUM_FRAMES = 441;
    /** 视频帧数下限（8*10+1） */
    public static final int MIN_NUM_FRAMES = 81;
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    /** 归一化任务状态 */
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String TASK_STATUS_FAILED = "FAILED";
    public static final String TASK_STATUS_PROCESSING = "PROCESSING";
    /** apiKey 为空 */
    public static final String ERROR_API_KEY_EMPTY = "密钥未配置";
    /** base_url 为空 */
    public static final String ERROR_BASE_URL_EMPTY = "网关未配置";
    /** api_suffix 为空 */
    public static final String ERROR_API_SUFFIX_EMPTY = "路径未配置";
    /** 未生成图片 */
    public static final String ERROR_NO_IMAGE = "出图失败";

    /** 图片尺寸不在当前模型支持范围 */
    public static final String ERROR_SIZE_UNSUPPORTED = "图片尺寸不支持";

    /** 图片比例不在当前模型支持范围 */
    public static final String ERROR_RATIO_UNSUPPORTED = "图片比例不支持";

    /** 当前模型不支持 ratio 参数 */
    public static final String ERROR_RATIO_DISABLED = "该模型不支持比例";

    /** 精确像素尺寸不能同时携带 ratio 参数 */
    public static final String ERROR_SIZE_RATIO_CONFLICT = "尺寸比例不能同传";
    /** OSS 上传默认后缀 */
    public static final String IMAGE_SUFFIX_PNG = ".png";
    /** OSS 上传默认 MIME */
    public static final String IMAGE_CONTENT_TYPE_PNG = "image/png";
    /** 日志响应截断长度 */
    public static final int LOG_RESPONSE_SNIPPET_MAX = 500;
}
