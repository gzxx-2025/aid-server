package com.aid.compose.config;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 视频媒体处理配置属性（category=mps，保留分类名以兼容已有部署）。
 *
 * @author 视觉AID
 */
@Data
public class MpsProperties {

    public static final String DEFAULT_FFMPEG_PATH = "/opt/aid-ffmpeg/current/ffmpeg";
    public static final String DEFAULT_FFPROBE_PATH = "/opt/aid-ffmpeg/current/ffprobe";
    public static final String DEFAULT_CJK_FONT_PATH = "/opt/aid-fonts/current/aid-cjk-font";

    /** tencent-mps / aliyun-ims / local-ffmpeg */
    private String processMode = "tencent-mps";

    /** 媒体处理总开关 */
    private Boolean enabled = false;

    /** 腾讯云 SecretId */
    private String secretId;

    /** 腾讯云 SecretKey */
    private String secretKey;

    /** MPS 接口地域（公共参数，可空） */
    private String region = "ap-guangzhou";

    /** 腾讯云 MPS 回调地址。 */
    private String tencentCallbackUrl;

    /** 腾讯云 MPS 最大在途任务数。 */
    private int tencentMaxConcurrency = 5;

    /** 阿里云 IMS AccessKeyId。 */
    private String aliyunAccessKeyId;

    /** 阿里云 IMS AccessKeySecret。 */
    private String aliyunAccessKeySecret;

    /** 阿里云 IMS 地域，必须与 OSS Bucket 地域一致。 */
    private String aliyunRegion = "cn-shanghai";

    /** 阿里云 IMS 任务通知回调地址。 */
    private String aliyunCallbackUrl;

    /** 阿里云 IMS 最大在途任务数。 */
    private int aliyunMaxConcurrency = 5;

    /** FFmpeg 可执行文件路径。 */
    private String ffmpegPath = defaultRuntimePath("AID_FFMPEG_PATH", DEFAULT_FFMPEG_PATH);

    /** FFprobe 可执行文件路径。 */
    private String ffprobePath = defaultRuntimePath("AID_FFPROBE_PATH", DEFAULT_FFPROBE_PATH);

    /** FFmpeg 临时工作目录；空值使用 JVM 临时目录。 */
    private String ffmpegTempDir;

    /** 单个本地合成任务最长执行秒数。 */
    private int ffmpegTimeoutSeconds = 3600;

    /** 本地 FFmpeg 最大在途任务数。 */
    private int ffmpegMaxConcurrency = 2;

    /** FFmpeg 编码线程数，0 表示自动。 */
    private int ffmpegThreads = 0;

    /** 字幕字体文件绝对路径。 */
    private String ffmpegFontFile = DEFAULT_CJK_FONT_PATH;

    /** 旧腾讯云输出桶字段，仅用于历史配置读取兼容 */
    private String outputBucket;

    /** 旧腾讯云输出地域字段，仅用于历史配置读取兼容 */
    private String outputRegion;

    /** 输出对象目录前缀 */
    private String outputDir = "/compose_result/";

    /** 旧腾讯云回调字段，仅用于历史配置读取兼容 */
    private String callbackUrl;

    /** 默认输出分辨率档 */
    private String outputResolution = "FHD";

    /** 默认编码 */
    private String codec = "H.264";

    /** 旧腾讯云分辨率单价 JSON，仅用于历史配置迁移读取 */
    private String pricingTiers;

    /** 元→积分汇率 */
    private int creditRate = 100;

    /** 利润倍率 */
    private BigDecimal profitMultiplier = new BigDecimal("1.1");

    /** 当前处理引擎的平台计算费（元/分钟）；本地 FFmpeg 默认 0。 */
    private BigDecimal localUnitPrice = BigDecimal.ZERO;

    /** 成片字幕字号（如 5% / 40px），默认 5% */
    private String subtitleFontSize = "5%";

    /** 成片字幕单屏正文优先最大字数（最终显示收敛为 7～12 字；≤0 按默认值处理），默认 10 */
    private int subtitleMaxChars = 10;

    private static String defaultRuntimePath(String environmentName, String fallback)
    {
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
