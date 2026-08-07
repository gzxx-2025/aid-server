package com.aid.upgrade.dto;

import lombok.Data;

/**
 * 官方资源包初始化状态。
 *
 * @author 视觉AID
 */
@Data
public class OfficialAssetsStatusVo {

    /** 是否已初始化。 */
    private boolean initialized;

    /** 当前资源文件数量。 */
    private long fileCount;

    /** 当前资源总字节数。 */
    private long totalBytes;

    /** 应用实际写入目录。 */
    private String targetDirectory;

    /** 推荐上传的资源包文件名。 */
    private String recommendedArchiveName;

    /** 支持的最大上传字节数。 */
    private long maxUploadBytes;

    /** 后台上传失败时的服务器手工初始化命令。 */
    private String manualCommand;

    /** 状态说明。 */
    private String message;
}
