package com.aid.common.moderation;

import lombok.Data;

/**
 * 图片审查请求
 * - fileContent 与 fileUrl 二选一：优先使用 fileContent（字节），否则使用 fileUrl
 *
 * @author 视觉AID
 */
@Data
public class ModerationRequest
{
    /**
     * 图片字节内容（可空）
     */
    private byte[] fileContent;

    /**
     * 图片 URL（可空）
     */
    private String fileUrl;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 业务来源标识（如 storyboard_upload/common_upload/oss_upload）
     */
    private String bizSource;

    /**
     * 上传用户 ID
     */
    private Long userId;
}
