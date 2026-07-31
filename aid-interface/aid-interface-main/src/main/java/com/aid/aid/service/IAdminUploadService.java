package com.aid.aid.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.aid.aid.domain.dto.OssUploadResponse;

public interface IAdminUploadService
{
    OssUploadResponse uploadOne(MultipartFile file);

    List<OssUploadResponse> uploadList(List<MultipartFile> files);

    OssUploadResponse uploadAvatar(MultipartFile file);
}
