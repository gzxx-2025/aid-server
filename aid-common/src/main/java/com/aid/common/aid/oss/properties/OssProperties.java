package com.aid.common.aid.oss.properties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.Data;

/**
 * OSS配置属性
 *
 * @author 视觉AID
 */
@Data
public class OssProperties
{
    /**
     * 是否启用OSS
     */
    private Boolean enabled = false;

    /**
     * 上传模式：local=走本地存储；oss=阿里云OSS；cos=腾讯云COS。
     * 统一上传接口 /api/user/oss/upload 会根据该值分发。
     * 默认 oss，缺省或非法值按 oss 处理。
     */
    private String uploadMode = "oss";

    /**
     * OSS Endpoint
     */
    private String endpoint;

    /**
     * AccessKey ID
     */
    private String accessKeyId;

    /**
     * AccessKey Secret
     */
    private String accessKeySecret;

    /**
     * Bucket名称
     */
    private String bucketName;

    /**
     * 文件路径前缀
     */
    private String prefix;

    /**
     * CDN域名
     */
    private String cdnDomain;
    // 与阿里云OSS字段相互独立，便于在 oss / cos 之间自由切换而无需重新填写凭证。

    /**
     * 腾讯云COS 地域（Region），如 ap-guangzhou。
     * 取值参见 https://cloud.tencent.com/document/product/436/6224
     */
    private String cosRegion;

    /**
     * 腾讯云COS SecretId（对应阿里云 accessKeyId）
     */
    private String cosSecretId;

    /**
     * 腾讯云COS SecretKey（对应阿里云 accessKeySecret）
     */
    private String cosSecretKey;

    /**
     * 腾讯云COS Bucket 名称，命名格式为 BucketName-APPID，如 examplebucket-1250000000
     */
    private String cosBucketName;

    /**
     * 腾讯云COS 文件路径前缀
     */
    private String cosPrefix;

    /**
     * 腾讯云COS 源站/内网 endpoint 域名（相当于阿里云的 endpoint），如。
     */
    private String cosCdnDomain;

    /**
     * 本地上传访问域名（如 https://example.com）
     * 配置后本地上传返回 localDomain + /profile/upload/...
     * 未配置则保持相对路径 /profile/upload/...
     */
    private String localDomain;

    /**
     * 图片URL域名白名单（逗号分隔，可空）。
     * 用于在 cdnDomain / localDomain 之外，额外放行可信的外部图片域名前缀，
     * 例如历史 CDN、合作方图床等。命中白名单前缀的完整 URL 视为合法本站/可信资源。
     * 例如：https://old-cdn.example.com,https://partner-img.example.com
     */
    private String imageUrlWhitelist;

    /**
     * 最大文件大小（字节），默认5MB
     */
    private Long maxFileSize = 5 * 1024 * 1024L;

    /**
     * 允许上传的文件扩展名（逗号分隔）
     * 例如：jpg,jpeg,png,gif,bmp,webp,pdf,doc,docx
     */
    private String allowedExtensions = "jpg,jpeg,png,gif,bmp,webp";

    /**
     * 批量上传最大文件数量，默认3。
     * 统一上传接口 /api/user/oss/upload 会据此校验。
     */
    private Integer maxBatchCount = 3;

    /**
     * 分类型上传大小限制（后台「上传大小限制」区块动态配置，单位换算后为字节）。
     * 每一项代表一类文件（如 图片 / 视频 / 音频），含一组扩展名与该类的单文件大小上限。
     * 上传校验时按文件扩展名命中对应类型：命中则用该类型的上限；
     * 列表为空（未配置）时回退到全局 {@link #maxFileSize} + {@link #allowedExtensions} 旧逻辑。
     */
    private List<UploadTypeLimit> uploadTypeLimits = new ArrayList<>();

    /**
     * 单个文件类型的上传限制。
     */
    @Data
    public static class UploadTypeLimit
    {
        /** 类型名称（展示用，如 图片 / 视频 / 音频） */
        private String name;

        /** 该类型允许的扩展名集合（均为小写） */
        private Set<String> extensions = new HashSet<>();

        /** 该类型单文件大小上限（MB，展示与提示用） */
        private long maxSizeMb;

        /** 该类型单文件大小上限（字节，实际校验用） */
        private long maxBytes;
    }

    /**
     * 当前生效的对外 CDN/访问域名：oss / cos 模式统一返回 {@link #cdnDomain}（对外读取域名已合并统一）。
     * 本地模式不走该方法（本地走 localDomain）；COS 源站/内网 endpoint 见 {@link #cosCdnDomain}，仅下发上游时使用。
     *
     * @return 生效的对外访问域名
     */
    public String getEffectiveCdnDomain()
    {
        return cdnDomain;
    }

    /**
     * 当前生效的对象键前缀：cos 模式返回 {@link #cosPrefix}，其余返回 {@link #prefix}。
     *
     * @return 生效的路径前缀
     */
    public String getEffectivePrefix()
    {
        return "cos".equalsIgnoreCase(uploadMode) ? cosPrefix : prefix;
    }
}

