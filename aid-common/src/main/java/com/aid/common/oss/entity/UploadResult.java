package com.aid.common.oss.entity;

import lombok.Builder;
import lombok.Data;

/**
 * 上传返回体（与源 common-oss UploadResult 对齐）
 */
@Data
@Builder
public class UploadResult {

    /**
     * 文件路径/访问 URL
     */
    private String url;

    /**
     * 文件名
     */
    private String filename;
}
