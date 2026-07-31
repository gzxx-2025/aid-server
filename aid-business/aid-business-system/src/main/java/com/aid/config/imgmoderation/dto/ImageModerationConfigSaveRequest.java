package com.aid.config.imgmoderation.dto;

import lombok.Data;

/**
 * 图片内容安全审查配置保存请求。
 *
 * 对应 aid_config(category=image_moderation) 的各配置项；字段为空（null）则该项不更新。
 * 密钥类字段若提交脱敏串（含 ****）视为未修改，保留原值。
 *
 * @author 视觉AID
 */
@Data
public class ImageModerationConfigSaveRequest {

    /**
     * 是否启用图片内容安全审查（总开关）。
     */
    private Boolean enabled;

    /**
     * 审查服务商（预留多厂商，默认 tencent）。
     */
    private String provider;

    /**
     * 腾讯云地域。
     */
    private String tencentRegion;

    /**
     * 腾讯云 SecretId。
     */
    private String tencentSecretId;

    /**
     * 腾讯云 SecretKey（脱敏回写保护）。
     */
    private String tencentSecretKey;

    /**
     * COS 模式是否优先使用 FileUrl。
     */
    private Boolean prioritizeFileUrl;

    /**
     * 审查时机（AFTER_UPLOAD 等）。
     */
    private String moderationStage;

    /**
     * 命中 Review 是否按拦截处理。
     */
    private Boolean blockOnSuggestionReview;

    /**
     * 审查异常时是否放行（fail-open）。
     */
    private Boolean failOpenOnError;

    /**
     * 是否记录 Pass 的审查日志。
     */
    private Boolean logPassed;

    /**
     * 审查日志保留天数。
     */
    private Integer logRetentionDays;
}
