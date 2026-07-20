package com.aid.aid.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.aid.aid.domain.dto.OssUploadResponse;

/**
 * OSS文件上传业务服务接口
 *
 * @author 视觉AID
 */
public interface IOssBusinessService
{
    /**
     * 统一批量上传：支持 1..N 个文件，根据 aid_config `oss.uploadMode` 自动分发到本地或阿里云OSS。
     *
     * @param files 上传的文件列表，size 必须在 [1, maxBatchCount] 范围内
     * @param customDir 自定义子目录（可选）
     * @return 上传响应列表，顺序与入参一致
     */
    List<OssUploadResponse> uploadList(List<MultipartFile> files, String customDir);
}
