package com.aid.aid.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.moderation.UserImageUploadModerationGuard;
import com.aid.aid.service.IOssBusinessService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.exception.OssException;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.SecurityUtils;

import cn.hutool.core.collection.CollectionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OSS文件上传业务服务实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssBusinessServiceImpl implements IOssBusinessService
{
    /**
     * 批量上传兜底数量：当 aid_config 未配置或配置非法时使用
     */
    private static final int DEFAULT_MAX_BATCH_COUNT = 3;

    /**
     * OSS核心操作类
     */
    private final OssTemplate ossTemplate;

    /**
     * 媒体URL统一拼接器（读取层）：DB 存相对路径，出参拼 CDN/localDomain
     */
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 用户图片上传内容安全审查守卫：上传前逐张同步审查，命中违规直接抛业务异常，违规图不落存储
     */
    private final UserImageUploadModerationGuard imageModerationGuard;

    /**
     * 统一批量上传：根据 aid_config `oss.uploadMode` 分发到 local / oss；
     * 支持 1..N 个文件，上限由 aid_config `oss.maxBatchCount` 控制。
     * 单文件尺寸 / 扩展名校验在 {@link OssTemplate} 内部统一执行。
     */
    @Override
    public List<OssUploadResponse> uploadList(List<MultipartFile> files, String customDir)
    {
        // 列表非空校验
        if (CollectionUtil.isEmpty(files))
        {
            log.error("统一上传失败：文件列表为空");
            throw new OssException("请选择文件");
        }

        // 从 aid_config 读取批量上限
        int maxBatchCount = resolveMaxBatchCount();
        if (files.size() > maxBatchCount)
        {
            log.error("统一上传失败：文件数量超出限制，count={}, max={}", files.size(), maxBatchCount);
            throw new OssException("数量超限");
        }

        // 第一阶段：全量预校验（空文件/文件名长度/扩展名/大小）+ 图片内容安全审查（同步、上传前审），
        // 任一不通过整批失败；违规图绝不写入存储（无需"先传后删"）。
        for (MultipartFile file : files)
        {
            if (Objects.isNull(file) || file.isEmpty())
            {
                log.error("统一上传失败：存在空文件");
                throw new OssException("文件为空");
            }
            // 复用 OssTemplate 内部统一校验规则（大小/扩展名/文件名长度）
            ossTemplate.validate(file);
            // 仅图片需要读字节送审：视频/音频等大文件不整包载入内存（防并发上传 OOM）
            if (!imageModerationGuard.shouldModerate(file.getOriginalFilename()))
            {
                continue;
            }
            // 图片内容安全审查（上传前同步审）：命中违规直接抛业务异常，违规图不落存储
            byte[] bytes;
            try
            {
                bytes = file.getBytes();
            }
            catch (IOException e)
            {
                log.error("统一上传失败：读取文件字节异常, name={}", file.getOriginalFilename(), e);
                throw new OssException("文件读取失败");
            }
            imageModerationGuard.checkBytesOrThrow(bytes, file.getOriginalFilename(), "oss_upload", currentUserIdOrNull());
        }

        // 第二阶段：全部通过校验 + 审查后再上传，内部按 uploadMode 分发到 local/oss
        List<OssUploadResponse> responses = new ArrayList<>(files.size());
        for (MultipartFile file : files)
        {
            String originalFileName = file.getOriginalFilename();
            long fileSize = file.getSize();
            String url = ossTemplate.upload(file, customDir);
            String fullUrl = mediaUrlResolver.toFullUrl(url);
            responses.add(new OssUploadResponse(
                    fullUrl,
                    extractFileName(url),
                    originalFileName,
                    fileSize));
        }
        return responses;
    }

    /**
     * 解析批量上传最大张数：从 {@link OssProperties#getMaxBatchCount()} 读取，
     * 非法值（null 或 ≤0）回退到 {@link #DEFAULT_MAX_BATCH_COUNT}。
     */
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
    /**
     * 安全获取当前登录用户ID：取不到（未登录/无上下文）返回 null，避免审查日志因鉴权异常中断上传主流程。
     *
     * @return 当前用户ID，取不到返回 null
     */
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

    /**
     * 从URL中提取文件名
     *
     * @param url 文件URL
     * @return 文件名
     */
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
