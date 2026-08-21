package com.aid.config.storage.dto;

import lombok.Data;

/** 文件存储整组保存请求。 */
@Data
public class StorageConfigSaveRequest
{
    private Boolean enabled;
    private String uploadMode;
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
    private String prefix;
    private String cosRegion;
    private String cosSecretId;
    private String cosSecretKey;
    private String cosBucketName;
    private String cosPrefix;
    private String cosCdnDomain;
    private String qiniuAccessKey;
    private String qiniuSecretKey;
    private String qiniuBucketName;
    private String qiniuPrefix;
    private String resourceAccessDomain;
    private Integer modelSignedUrlExpireHours;
    private String imageUrlWhitelist;
    private Long maxFileSize;
    private String allowedExtensions;
    private Integer maxBatchCount;
    private String uploadTypeLimits;
}
