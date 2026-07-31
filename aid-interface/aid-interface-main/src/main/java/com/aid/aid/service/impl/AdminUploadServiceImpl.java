package com.aid.aid.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.moderation.UserImageUploadModerationGuard;
import com.aid.aid.service.IAdminUploadService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.exception.OssException;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUploadServiceImpl implements IAdminUploadService
{
    private static final int DEFAULT_MAX_BATCH_COUNT = 3;

    private static final String AVATAR_DIR = "avatar";

    private final OssTemplate ossTemplate;

    private final MediaUrlResolver mediaUrlResolver;

    private final UserImageUploadModerationGuard imageModerationGuard;

    @Override
    public OssUploadResponse uploadOne(MultipartFile file)
    {
        validateSingle(file, false);
        return doUpload(file, null);
    }

    @Override
    public List<OssUploadResponse> uploadList(List<MultipartFile> files)
    {
        if (CollectionUtil.isEmpty(files))
        {
            log.error("后台上传失败：文件列表为空");
            throw new OssException("请选择文件");
        }

        int maxBatchCount = resolveMaxBatchCount();
        if (files.size() > maxBatchCount)
        {
            log.error("后台上传失败：文件数量超限，count={}, max={}", files.size(), maxBatchCount);
            throw new OssException("数量超上限");
        }

        for (MultipartFile file : files)
        {
            validateSingle(file, false);
        }

        List<OssUploadResponse> responses = new ArrayList<>(files.size());
        for (MultipartFile file : files)
        {
            responses.add(doUpload(file, null));
        }
        return responses;
    }

    @Override
    public OssUploadResponse uploadAvatar(MultipartFile file)
    {
        validateSingle(file, true);
        return doUpload(file, AVATAR_DIR);
    }

    private void validateSingle(MultipartFile file, boolean imageOnly)
    {
        if (Objects.isNull(file) || file.isEmpty())
        {
            log.error("后台上传失败：文件为空");
            throw new OssException("文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (imageOnly)
        {
            String rawExtension = FilenameUtils.getExtension(originalFilename);
            String extension = StrUtil.isBlank(rawExtension) ? "" : rawExtension.toLowerCase(Locale.ROOT);
            if (!isAllowedAvatarExtension(extension))
            {
                log.error("头像上传失败：文件类型错误，extension={}", extension);
                throw new OssException("文件类型错误");
            }
        }

        ossTemplate.validate(file);
        try
        {
            imageModerationGuard.checkBytesOrThrow(file.getBytes(), originalFilename,
                    imageOnly ? "avatar_upload" : "admin_common_upload", currentUserIdOrNull());
        }
        catch (IOException e)
        {
            log.error("后台上传失败：读取文件异常，name={}", originalFilename, e);
            throw new OssException("文件读取失败");
        }
    }

    private OssUploadResponse doUpload(MultipartFile file, String customDir)
    {
        String relativeUrl = ossTemplate.upload(file, customDir);
        String fullUrl = mediaUrlResolver.toFullUrl(relativeUrl);
        return new OssUploadResponse(fullUrl, extractFileName(relativeUrl), file.getOriginalFilename(), file.getSize());
    }

    private int resolveMaxBatchCount()
    {
        OssProperties properties = ossTemplate.getProperties();
        if (Objects.isNull(properties) || Objects.isNull(properties.getMaxBatchCount())
                || properties.getMaxBatchCount() <= 0)
        {
            return DEFAULT_MAX_BATCH_COUNT;
        }
        return properties.getMaxBatchCount();
    }

    private boolean isAllowedAvatarExtension(String extension)
    {
        return "jpg".equals(extension)
                || "jpeg".equals(extension)
                || "png".equals(extension)
                || "gif".equals(extension)
                || "bmp".equals(extension)
                || "webp".equals(extension);
    }

    private Long currentUserIdOrNull()
    {
        try
        {
            return SecurityUtils.getUserId();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String extractFileName(String url)
    {
        if (Objects.isNull(url))
        {
            return "";
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1)
        {
            return url.substring(lastSlash + 1);
        }
        return url;
    }
}
