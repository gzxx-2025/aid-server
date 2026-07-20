package com.aid.common.aid.oss.vo;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 上传大小限制视图对象。
 * 供前端在上传前按文件类型做大小预校验，与后台「文件存储 → 上传大小限制」
 * (aid_config category=oss, config_name=uploadTypeLimits) 保持一致，确保前后端限制统一。
 *
 * @author 视觉AID
 */
@Data
public class OssUploadLimitsVO
{
    /** 分类型上传限制列表（后台动态配置，按文件后缀命中类型后校验大小） */
    private List<TypeLimit> typeLimits = new ArrayList<>();

    /** 全局兜底单文件上限（MB）：未配置分类型限制时使用 */
    private long globalMaxSizeMb;

    /** 全局兜底允许的扩展名（逗号分隔）：未配置分类型限制时使用 */
    private String globalAllowedExtensions;

    /**
     * 单个类型的上传限制。
     */
    @Data
    public static class TypeLimit
    {
        /** 类型名称（如 图片 / 视频 / 音频） */
        private String name;

        /** 该类型单文件大小上限（MB） */
        private long maxSizeMb;

        /** 该类型允许的扩展名（小写、不含点） */
        private List<String> extensions = new ArrayList<>();
    }
}
