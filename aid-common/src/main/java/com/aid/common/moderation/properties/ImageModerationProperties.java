package com.aid.common.moderation.properties;

import lombok.Data;

/**
 * 图片内容安全审查配置属性
 * - 对应 aid_config 中 category=image_moderation 的配置项
 *
 * @author 视觉AID
 */
@Data
public class ImageModerationProperties
{
    /**
     * 是否启用图片内容安全审查（总开关）
     */
    private boolean enabled = false;

    /**
     * 审查服务商，预留多厂商扩展，默认 tencent
     */
    private String provider = "tencent";

    /**
     * 腾讯云地域（Region），如 ap-shanghai
     */
    private String tencentRegion = "ap-shanghai";

    /**
     * 腾讯云 SecretId
     */
    private String tencentSecretId = "";

    /**
     * 腾讯云 SecretKey
     */
    private String tencentSecretKey = "";

    /**
     * COS 模式下是否优先使用 FileUrl 交给 IMS 自行拉取（腾讯云内部互联，省下载）
     */
    private boolean prioritizeFileUrl = true;

    /**
     * 审查时机（预留，当前未生效）：审查时机由各上传入口按场景固定——
     * 本地写盘走上传前审(checkBytesOrThrow)、云存储走上传后审(checkUploadedOrThrow)，
     * 不依赖本字段做全局切换。保留以便后续扩展。
     */
    private String moderationStage = "AFTER_UPLOAD";

    /**
     * 命中 Review 建议时是否按拦截处理（true=拦截，false=放行但标记）
     */
    private boolean blockOnSuggestionReview = true;

    /**
     * 审查服务异常时是否放行（fail-open）。默认 false=不放行：服务异常时拒绝上传，宁缺毋滥。
     */
    private boolean failOpenOnError = false;

    /**
     * 是否记录通过（Pass）的审查日志
     */
    private boolean logPassed = false;

    /**
     * 审查日志保留天数
     */
    private int logRetentionDays = 90;
}
