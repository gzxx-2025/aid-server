package com.aid.script.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户自动保存剧本请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserScriptAutoSaveRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 集数ID(电影传0) */
    @NotNull(message = "集数ID不能为空")
    private Long episodeId;

    /** 剧本原文内容，允许空字符串 */
    @NotNull(message = "剧本内容不能为空")
    private String originalText;

    /** 客户端编辑基线剧本ID；0表示基线中尚无剧本 */
    private Long baseScriptId;

    /** 客户端编辑基线正文哈希 */
    private String baseContentHash;
}
