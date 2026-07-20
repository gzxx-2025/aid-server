package com.aid.prompt.dto;

import lombok.Data;

/**
 * 提示词查询请求DTO（查询个人+官方合并列表）
 *
 * @author 视觉AID
 */
@Data
public class PromptLibQueryRequest {

    /** 提示词分类: style/camera/subject */
    private String promptType;

    /** 提示词名称（模糊查询） */
    private String promptName;
}
