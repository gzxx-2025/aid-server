package com.aid.compose.config;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 腾讯云 MPS 视频合成配置属性（category=mps）。
 *
 * @author 视觉AID
 */
@Data
public class MpsProperties {

    /** MPS 合成总开关：false 或密钥为空均视为未配置 */
    private Boolean enabled = false;

    /** 腾讯云 SecretId */
    private String secretId;

    /** 腾讯云 SecretKey */
    private String secretKey;

    /** MPS 接口地域（公共参数，可空） */
    private String region = "ap-guangzhou";

    /** 成片输出 COS 桶 */
    private String outputBucket;

    /** 输出桶地域 */
    private String outputRegion;

    /** 输出对象目录前缀 */
    private String outputDir = "/compose_result/";

    /** MPS 任务通知回调地址 */
    private String callbackUrl;

    /** 默认输出分辨率档 */
    private String outputResolution = "FHD";

    /** 默认编码 */
    private String codec = "H.264";

    /** 分辨率档单价（元/分钟）JSON 原文 */
    private String pricingTiers;

    /** 元→积分汇率 */
    private int creditRate = 100;

    /** 利润倍率 */
    private BigDecimal profitMultiplier = new BigDecimal("1.1");

    /** 成片字幕字号（如 5% / 40px），默认 5% */
    private String subtitleFontSize = "5%";

    /** 成片字幕每行最大字数（超出自动换行；≤0 不折行），默认 18 */
    private int subtitleWrapChars = 18;
}
