package com.aid.upgrade.dto;

import org.springframework.web.multipart.MultipartFile;

import lombok.Data;

/**
 * HTTPS 证书对上传参数。
 *
 * @author 视觉AID
 */
@Data
public class HttpsCertificateUploadDto {

    /** PEM 格式完整证书链 */
    private MultipartFile certificate;

    /** PEM 格式私钥 */
    private MultipartFile privateKey;

    /** 配置文件路径，留空使用当前生效路径 */
    private String configPath;

    /** 证书应覆盖的用户端域名 */
    private String httpsPublicDomain;

    /** 证书应覆盖的管理端域名 */
    private String httpsAdminDomain;
}
