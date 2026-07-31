package com.aid.script.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户保存剧本请求DTO（创建/版本更新/静默保存统一参数）
 *
 * @author 视觉AID
 */
@Data
public class UserScriptSaveRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 集数ID(电影传0) */
    @NotNull(message = "集数ID不能为空")
    private Long episodeId;

    /** 剧本原文内容 */
    @NotBlank(message = "剧本内容不能为空")
    private String originalText;
}
