package com.aid.aid.domain.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OSS文件上传响应DTO
 *
 * @author 视觉AID
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OssUploadResponse
{
    /**
     * 文件访问URL
     */
    @MediaUrl
    private String url;

    /**
     * 存储的文件名
     */
    private String fileName;

    /**
     * 原始文件名
     */
    private String originalFileName;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;
}
