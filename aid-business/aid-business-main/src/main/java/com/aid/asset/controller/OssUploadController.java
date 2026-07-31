package com.aid.asset.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.aid.service.IOssBusinessService;
import com.aid.common.core.domain.AjaxResult;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件上传接口（C端） —— 统一入口。
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/oss")
public class OssUploadController
{
    /**
     * 文件上传业务服务
     */
    @Resource
    private IOssBusinessService ossBusinessService;

    /**
     * 统一批量上传：支持 1..N 个文件，按 aid_config `oss.uploadMode` 自动分发到本地或阿里云OSS。
     *
     * @param files 上传的文件列表（1..N，上限由 oss.maxBatchCount 控制）
     * @param customDir 自定义子目录（可选，仅 OSS 模式生效）
     * @return 上传结果列表（顺序与入参一致）
     */
    @PostMapping("/upload")
    public AjaxResult upload(@RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "customDir", required = false) String customDir)
    {
        try
        {
            // 由业务层按 uploadMode 自动分发，并完成 maxBatchCount / maxFileSize / allowedExtensions 校验
            List<OssUploadResponse> responses = ossBusinessService.uploadList(files, customDir);

            AjaxResult ajax = AjaxResult.success("上传成功");
            ajax.put("data", responses);
            ajax.put("count", responses.size());
            return ajax;
        }
        catch (RuntimeException e)
        {
            // 业务级错误（数量超限、文件过大、类型错误等）保留原始文案返回给前端
            log.error("统一上传失败：{}", e.getMessage(), e);
            return AjaxResult.error(e.getMessage());
        }
        catch (Exception e)
        {
            log.error("统一上传失败：{}", e.getMessage(), e);
            return AjaxResult.error("上传失败，请重试");
        }
    }
}
