package com.aid.config.mps.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 腾讯云 MPS 视频合成配置保存请求（后台）。
 * 字段为 null 的不更新；密钥提交脱敏串（含 ****）视为未修改，保留原值。
 *
 * @author 视觉AID
 */
@Data
public class MpsConfigSaveRequest {

    /** 合成功能总开关 */
    private Boolean enabled;

    /** 腾讯云 SecretId（密钥，脱敏回写保护） */
    private String secretId;

    /** 腾讯云 SecretKey（密钥，脱敏回写保护） */
    private String secretKey;

    /** MPS 接口地域 */
    private String region;

    /** 成片输出 COS 桶 */
    private String outputBucket;

    /** 输出桶地域 */
    private String outputRegion;

    /** 输出对象目录前缀 */
    private String outputDir;

    /** MPS 任务通知回调地址 */
    private String callbackUrl;

    /** 默认输出分辨率档（SD/HD/FHD/2K/4K） */
    private String outputResolution;

    /** 默认编码 */
    private String codec;

    /** 分辨率档单价 JSON（元/分钟） */
    private String pricingTiers;

    /** 元 → 积分汇率 */
    private Integer creditRate;

    /** 利润倍率 */
    private BigDecimal profitMultiplier;
}
